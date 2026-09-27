package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
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
 * An Access Port. While a job has sent a set of ingredients into its machine, the port is locked to that job and
 * takes in whatever arrives (from a pipe, a hopper, or the machine itself) into a small intake, which the job empties
 * every tick. At any other time it refuses everything, so nothing ends up where no job waits for it. Nothing can be
 * pulled out of it.
 */
public class AccessPortBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOTS = 9;
    public static final int CAPACITY = 5_000;
    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    /** Ticks between checks that the job holding the lock still exists. */
    private static final int LOCK_CHECK = 40;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** The job whose sets are in the machine, and which of its steps they are for. */
    private @Nullable UUID lockJob;
    private int lockStep;
    /** A name the player gave the port; empty for the machine's own name. */
    private String label = "";

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case AccessPortMenu.DATA_ENERGY_LOW -> energy.getAmountAsInt() & 0xFFFF;
                case AccessPortMenu.DATA_ENERGY_HIGH -> energy.getAmountAsInt() >>> 16;
                case AccessPortMenu.DATA_RUNNING -> running() ? 1 : 0;
                case AccessPortMenu.DATA_LOCKED -> lockJob != null ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return AccessPortMenu.DATA_COUNT;
        }
    };

    public AccessPortBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ACCESS_PORT_ENTITY.get(), pos, state, CAPACITY);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, AccessPortBlockEntity port) {
        port.payForTick();
        if (port.lockJob != null && level.getGameTime() % LOCK_CHECK == 0) {
            // A job that ended without letting go (its server was broken in an unloaded spot, say) lets go now.
            AutocraftState.Job job = AutocraftState.get(((ServerLevel) level).getServer()).job(port.lockJob).orElse(null);
            if (job == null || job.finished()) {
                port.unlock();
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (lockJob != null && level instanceof ServerLevel serverLevel) {
            Jobs.writeNow(serverLevel.getServer(), lockJob);
        }
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.PORT_DRAIN.getAsInt();
    }

    /** Toward the machine. */
    public Direction facing() {
        return getBlockState().getValue(AccessPortBlock.FACING);
    }

    /** Where the machine stands. */
    public BlockPos machinePos() {
        return worldPosition.relative(facing());
    }

    // --- the lock ---

    public @Nullable UUID lockJob() {
        return lockJob;
    }

    public int lockStep() {
        return lockStep;
    }

    /** Whether step {@code step} of job {@code job} may send sets here: nobody else holds the port. */
    public boolean freeFor(UUID job, int step) {
        return lockJob == null || lockJob.equals(job) && lockStep == step;
    }

    public void lock(UUID job, int step) {
        if (!job.equals(lockJob) || lockStep != step) {
            lockJob = job;
            lockStep = step;
            setChanged();
        }
    }

    public void unlock() {
        if (lockJob != null) {
            lockJob = null;
            lockStep = 0;
            setChanged();
        }
    }

    // --- the name ---

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        String cleaned = label.strip();
        this.label = cleaned.length() > AccessPortMenu.MAX_NAME ? cleaned.substring(0, AccessPortMenu.MAX_NAME) : cleaned;
        setChanged();
    }

    /** The port's name if it has one, otherwise the machine's. */
    public Component machineName() {
        return label.isEmpty() ? Machines.blockName(level, machinePos()) : Component.literal(label);
    }

    // --- the intake ---

    /** Takes what arrived out of the intake, for the job holding the lock. */
    NonNullList<ItemStack> takeIntake() {
        NonNullList<ItemStack> taken = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            if (!items.get(i).isEmpty()) {
                taken.set(i, items.get(i));
                items.set(i, ItemStack.EMPTY);
                any = true;
            }
        }
        if (any) {
            setChanged();
        }
        return taken;
    }

    /** Whether the port takes items in now: locked to a job, and powered. */
    public boolean open() {
        return lockJob != null && running();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return open();
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return open();
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    public ContainerData data() {
        return data;
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
    public Component getName() {
        return label.isEmpty() ? super.getName() : Component.literal(label);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new AccessPortMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    /** What the screen needs when it opens: where the port is, its name and the machine's. */
    public void writeOpening(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(worldPosition);
        buf.writeUtf(label, AccessPortMenu.MAX_NAME);
        ComponentSerialization.STREAM_CODEC.encode(buf, Machines.blockName(level, machinePos()));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.storeNullable("lock_job", UUIDUtil.CODEC, lockJob);
        output.putInt("lock_step", lockStep);
        output.putString("label", label);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        lockJob = input.read("lock_job", UUIDUtil.CODEC).orElse(null);
        lockStep = input.getIntOr("lock_step", 0);
        label = input.getStringOr("label", "");
    }

    /** The mined item keeps the port's name. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!label.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component name = components.get(DataComponents.CUSTOM_NAME);
        label = name == null ? "" : name.getString();
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("label");
    }
}
