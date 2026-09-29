package dev.micolash.jasm.crystal;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The Crystal Foundry: grows a fixed number of Data Crystals from each Crystal Seed and cuts each straight into a Blank
 * Chip. A seed is used up as soon as it starts growing.
 */
public class CrystalFoundryBlockEntity extends MachineBlockEntity {
    public static final int INPUT = 0;
    public static final int OUTPUT_FIRST = 1;
    public static final int OUTPUT_COUNT = 9;
    public static final int SLOTS = OUTPUT_FIRST + OUTPUT_COUNT;
    public static final int CAPACITY = 50_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final ResourceHandler<ItemResource> automation = new Automation(VanillaContainerWrapper.of(this));
    private boolean growing;
    private int made;
    private int progress;
    /** Whether the last tick grew the crystal along; only for the power it takes. */
    private boolean busy;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case CrystalFoundryMenu.DATA_PROGRESS -> progress;
                case CrystalFoundryMenu.DATA_TICKS -> JasmConfig.FOUNDRY_TICKS_PER_CRYSTAL.getAsInt();
                case CrystalFoundryMenu.DATA_MADE -> made;
                case CrystalFoundryMenu.DATA_PER_SEED -> JasmConfig.FOUNDRY_CRYSTALS_PER_SEED.getAsInt();
                case CrystalFoundryMenu.DATA_ENERGY_LOW -> energy.getAmountAsInt() & 0xFFFF;
                case CrystalFoundryMenu.DATA_ENERGY_HIGH -> energy.getAmountAsInt() >>> 16;
                case CrystalFoundryMenu.DATA_FLAGS -> (growing ? CrystalFoundryMenu.FLAG_GROWING : 0) | (running() ? CrystalFoundryMenu.FLAG_POWERED : 0)
                        | (growing && !roomForOne() ? CrystalFoundryMenu.FLAG_FULL : 0);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return CrystalFoundryMenu.DATA_COUNT;
        }
    };

    public CrystalFoundryBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), pos, state, CAPACITY);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CrystalFoundryBlockEntity foundry) {
        foundry.tick();
        foundry.syncGrowth(level);
    }

    /** What players were last told: 1 growing, 2 busy, and the progress it was at. -1 until the first tick. */
    private int sentState = -1;
    private int sentProgress;
    private int sinceSent;

    /** Tells players when the crystal starts, stops, pauses or begins its next round, and now and then to keep their clocks true. */
    private void syncGrowth(Level level) {
        int state = (growing ? 1 : 0) | (busy ? 2 : 0);
        boolean restarted = progress < sentProgress;
        if (state != sentState || restarted || busy && ++sinceSent >= 40) {
            sentState = state;
            sentProgress = progress;
            sinceSent = 0;
            BlockState block = getBlockState();
            level.sendBlockUpdated(worldPosition, block, block, Block.UPDATE_CLIENTS);
        }
    }

    private boolean shownGrowing;
    private boolean shownBusy;
    private int shownProgress;
    private int shownTicks = 1;
    private long shownAt;

    /** How far along the crystal in the chamber is, 0 to 1, as the player's game sees it. -1 when nothing is growing. */
    public float shownGrowth(long gameTime, float partialTicks) {
        if (!shownGrowing) {
            return -1;
        }
        float elapsed = shownBusy ? gameTime - shownAt + partialTicks : 0;
        return Math.clamp((shownProgress + elapsed) / shownTicks, 0F, 1F);
    }

    // Players only get how the crystal is coming along, not the slots.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("growing", growing);
        tag.putBoolean("busy", busy);
        tag.putInt("progress", progress);
        tag.putInt("ticks", JasmConfig.FOUNDRY_TICKS_PER_CRYSTAL.getAsInt());
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shown(input);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        shown(input);
    }

    private void shown(ValueInput input) {
        shownGrowing = input.getBooleanOr("growing", false);
        shownBusy = input.getBooleanOr("busy", false);
        shownProgress = input.getIntOr("progress", 0);
        shownTicks = Math.max(1, input.getIntOr("ticks", 1));
        shownAt = level == null ? 0 : level.getGameTime();
    }

    private void tick() {
        busy = false;
        if (!growing && items.get(INPUT).is(JasmItems.CRYSTAL_SEED.get())) {
            items.get(INPUT).shrink(1);
            growing = true;
            made = 0;
            progress = 0;
            setChanged();
        }
        if (!growing || !roomForOne()) {
            payForTick();
            return;
        }
        busy = true;
        if (!payForTick()) {
            return;
        }
        if (++progress >= JasmConfig.FOUNDRY_TICKS_PER_CRYSTAL.getAsInt()) {
            progress = 0;
            output();
            if (++made >= JasmConfig.FOUNDRY_CRYSTALS_PER_SEED.getAsInt()) {
                growing = false;
                made = 0;
            }
        }
        setChanged();
    }

    private boolean roomForOne() {
        for (int slot = OUTPUT_FIRST; slot < SLOTS; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || stack.is(JasmItems.BLANK_CHIP.get()) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** One Blank Chip into the output, onto a matching stack first. {@link #roomForOne} made sure it fits. */
    private void output() {
        for (int slot = OUTPUT_FIRST; slot < SLOTS; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(JasmItems.BLANK_CHIP.get()) && stack.getCount() < stack.getMaxStackSize()) {
                stack.grow(1);
                return;
            }
        }
        for (int slot = OUTPUT_FIRST; slot < SLOTS; slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, new ItemStack(JasmItems.BLANK_CHIP.get()));
                return;
            }
        }
    }

    @Override
    public int drainPerTick() {
        return busy ? JasmConfig.FOUNDRY_DRAIN.getAsInt() : 0;
    }

    public boolean growing() {
        return growing;
    }

    /** Crystals made so far from the seed that is growing. */
    public int made() {
        return made;
    }

    public int progress() {
        return progress;
    }

    public ContainerData data() {
        return data;
    }

    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    public static boolean accepts(int slot, ItemStack stack) {
        return slot == INPUT && stack.is(JasmItems.CRYSTAL_SEED.get());
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
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
        return new CrystalFoundryMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition), getBlockState().getBlock());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putBoolean("growing", growing);
        output.putInt("made", made);
        output.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        growing = input.getBooleanOr("growing", false);
        made = Math.max(0, input.getIntOr("made", 0));
        progress = Math.max(0, input.getIntOr("progress", 0));
    }

    /** Hoppers and pipes: Crystal Seeds in, Blank Chips out. */
    private static final class Automation extends DelegatingResourceHandler<ItemResource> {
        Automation(ResourceHandler<ItemResource> slots) {
            super(slots);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index == INPUT && accepts(INPUT, resource.toStack(1)) ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return insert(INPUT, resource, amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index >= OUTPUT_FIRST ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            int taken = 0;
            for (int slot = OUTPUT_FIRST; slot < SLOTS && taken < amount; slot++) {
                taken += extract(slot, resource, amount - taken, transaction);
            }
            return taken;
        }
    }
}
