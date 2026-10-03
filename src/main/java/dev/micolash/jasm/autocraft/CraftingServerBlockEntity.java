package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A Crafting Server: four Processor slots and four Storage Module slots, and at most one job. Its parts can't be taken
 * out while a job runs. Broken, it drops its parts and spills whatever its job held.
 */
public class CraftingServerBlockEntity extends MachineBlockEntity {
    public static final int PROCESSOR_SLOTS = 4;
    public static final int MEMORY_SLOTS = 4;
    public static final int SLOTS = PROCESSOR_SLOTS + MEMORY_SLOTS;
    public static final int CAPACITY = 100_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private @Nullable CraftingJob job;
    /** Checked once after loading: a job the list of running jobs says is here, but the block lost in a crash. */
    private boolean adoptChecked;
    /** What the job makes, for the screen. */
    private final SimpleContainer shown = new SimpleContainer(1);
    /** The parts and power as a player's game last heard them, packed by {@link #looks()}: all it needs to draw the block. */
    private int shownLooks;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            CraftingJob j = job;
            return switch (index) {
                case CraftingServerMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case CraftingServerMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case CraftingServerMenu.DATA_RUNNING -> running() ? 1 : 0;
                case CraftingServerMenu.DATA_PARALLEL -> parallel();
                case CraftingServerMenu.DATA_MEMORY_LOW -> ContainerWords.low(memory());
                case CraftingServerMenu.DATA_MEMORY_HIGH -> ContainerWords.high(memory());
                case CraftingServerMenu.DATA_PHASE -> j == null ? 0 : j.phase().ordinal() + 1;
                case CraftingServerMenu.DATA_PROGRESS -> j == null ? 0 : Math.round(j.progress() * 1000);
                case CraftingServerMenu.DATA_ACTIVE -> j == null ? 0 : j.runningCount();
                case CraftingServerMenu.DATA_PAUSE -> j == null ? 0 : j.pause().code();
                case CraftingServerMenu.DATA_AMOUNT_LOW -> j == null ? 0 : ContainerWords.low((int) Math.min(Integer.MAX_VALUE, j.amount()));
                case CraftingServerMenu.DATA_AMOUNT_HIGH -> j == null ? 0 : ContainerWords.high((int) Math.min(Integer.MAX_VALUE, j.amount()));
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return CraftingServerMenu.DATA_COUNT;
        }
    };

    public CraftingServerBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CRAFTING_SERVER_ENTITY.get(), pos, state, CAPACITY);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, CraftingServerBlockEntity server) {
        boolean powered = server.payForTick();
        ServerLevel serverLevel = (ServerLevel) level;
        if (!server.adoptChecked) {
            server.adoptChecked = true;
            Jobs.adopt(serverLevel, server);
        }
        if (server.job != null) {
            Jobs.tick(serverLevel, server, powered);
        }
        CraftingJob job = server.job;
        // What the job makes; the screen writes the amount beside it.
        server.shown.setItem(0, job == null || job.target() == null ? ItemStack.EMPTY : job.target().create().copyWithCount(1));
        server.syncLooks(server.looks());
    }

    /** Three bits per slot, the part's tier plus one (0 when empty), then one bit for power. */
    private int looks() {
        int bits = 0;
        for (int i = 0; i < SLOTS; i++) {
            Enum<?> tier = i < PROCESSOR_SLOTS ? ServerPartItem.processorOf(items.get(i)) : ServerPartItem.memoryOf(items.get(i));
            if (tier != null) {
                bits |= (tier.ordinal() + 1) << (3 * i);
            }
        }
        return running() ? bits | 1 << (3 * SLOTS) : bits;
    }

    /** The tier of the Processor in {@code slot} as a player's game knows it, or null. */
    public @Nullable ProcessorTier shownProcessor(int slot) {
        int code = shownLooks >> (3 * slot) & 7;
        return code == 0 ? null : ProcessorTier.values()[code - 1];
    }

    /** The tier of the Storage Module in module slot {@code slot} (0 to 3) as a player's game knows it, or null. */
    public @Nullable MemoryTier shownModule(int slot) {
        int code = shownLooks >> (3 * (PROCESSOR_SLOTS + slot)) & 7;
        return code == 0 ? null : MemoryTier.values()[code - 1];
    }

    /** Whether the fans turn: the server has power. */
    public boolean shownRunning() {
        return (shownLooks >> (3 * SLOTS) & 1) != 0;
    }

    /** A job with sets out in machines has its items written as this chunk is saved (see {@link Jobs#writeNow}). */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (job != null && job.sentCount() > 0 && level instanceof ServerLevel serverLevel) {
            Jobs.writeNow(serverLevel.getServer(), job.id());
        }
    }

    @Override
    public int drainPerTick() {
        int drain = JasmConfig.SERVER_DRAIN.getAsInt();
        for (int i = 0; i < PROCESSOR_SLOTS; i++) {
            ProcessorTier tier = ServerPartItem.processorOf(items.get(i));
            if (tier != null) {
                drain += tier.drainPerTick();
            }
        }
        return drain;
    }

    /** Crafts this server runs at the same time. */
    public int parallel() {
        int crafts = 0;
        for (int i = 0; i < PROCESSOR_SLOTS; i++) {
            ProcessorTier tier = ServerPartItem.processorOf(items.get(i));
            if (tier != null) {
                crafts += tier.crafts();
            }
        }
        return crafts;
    }

    /** Items a job here may hold. */
    public int memory() {
        int capacity = 0;
        for (int i = PROCESSOR_SLOTS; i < SLOTS; i++) {
            MemoryTier tier = ServerPartItem.memoryOf(items.get(i));
            if (tier != null) {
                capacity += tier.capacity();
            }
        }
        return capacity;
    }

    public @Nullable CraftingJob job() {
        return job;
    }

    void setJob(@Nullable CraftingJob job) {
        this.job = job;
        setChanged();
    }

    public boolean busy() {
        return job != null;
    }

    public SimpleContainer shown() {
        return shown;
    }

    public ContainerData data() {
        return data;
    }

    public static boolean accepts(int slot, ItemStack stack) {
        return slot < PROCESSOR_SLOTS ? ServerPartItem.processorOf(stack) != null : ServerPartItem.memoryOf(stack) != null;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
    }

    /** Nothing leaves while a job runs, not even through a hopper. */
    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return job == null;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new CraftingServerMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    /** Parts drop as usual; the job is stopped and everything it held spills on the ground. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel && job != null) {
            Jobs.spill(serverLevel, this);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.storeNullable("job", CraftingJob.CODEC, job);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        job = input.read("job", CraftingJob.CODEC).orElse(null);
    }

    // Players only get what the front and back show, not the parts or the job.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("looks", looks());
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shownLooks = input.getIntOr("looks", 0);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        shownLooks = input.getIntOr("looks", 0);
    }
}
