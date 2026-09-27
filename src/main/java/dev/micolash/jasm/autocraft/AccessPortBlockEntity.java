package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * An Access Port. Every block touching it that takes items (a furnace, a modded machine, a multiblock's input) is a
 * machine the network can use; cables and other crafting blocks are not. Which sides have machines is looked up each
 * time, so machines placed or removed later count at once.
 *
 * <p>While a job has sent a set of ingredients into one of its machines, that side is locked to the job and the port
 * takes in whatever arrives (from a pipe, a hopper, or a machine itself) into a small intake, which the jobs empty
 * every tick. With no side locked it refuses everything, so nothing ends up where no job waits for it. Nothing can be
 * pulled out of it.
 */
public class AccessPortBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOTS = 9;
    public static final int CAPACITY = 5_000;
    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    /** Ticks between checks that the jobs holding locks still exist. */
    private static final int LOCK_CHECK = 40;

    /** Which job's sets are in the machine on one side, and which of its steps they are for. */
    public record Lock(UUID job, int step) {}

    private record SavedLock(Direction side, UUID job, int step) {
        static final Codec<SavedLock> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Direction.CODEC.fieldOf("side").forGetter(SavedLock::side),
                        UUIDUtil.CODEC.fieldOf("job").forGetter(SavedLock::job),
                        Codec.INT.fieldOf("step").forGetter(SavedLock::step))
                .apply(i, SavedLock::new));
    }

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final Map<Direction, Lock> locks = new EnumMap<>(Direction.class);
    /** A name the player gave the port; empty for the machines' own names. */
    private String label = "";

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case AccessPortMenu.DATA_ENERGY_LOW -> energy.getAmountAsInt() & 0xFFFF;
                case AccessPortMenu.DATA_ENERGY_HIGH -> energy.getAmountAsInt() >>> 16;
                case AccessPortMenu.DATA_RUNNING -> running() ? 1 : 0;
                case AccessPortMenu.DATA_LOCKED -> locks.size();
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
        if (level.getGameTime() % 10 == 0) {
            // A machine can start taking items without its block changing (a modded one finishing its build, say).
            port.refreshSides();
        }
        if (!port.locks.isEmpty() && level.getGameTime() % LOCK_CHECK == 0) {
            // A job that ended without letting go (its server was broken in an unloaded spot, say) lets go now.
            AutocraftState autocraft = AutocraftState.get(((ServerLevel) level).getServer());
            for (UUID id : port.lockedJobs()) {
                AutocraftState.Job job = autocraft.job(id).orElse(null);
                if (job == null || job.finished()) {
                    port.unlockJob(id);
                }
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel serverLevel) {
            for (UUID job : lockedJobs()) {
                Jobs.writeNow(serverLevel.getServer(), job);
            }
        }
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.PORT_DRAIN.getAsInt();
    }

    // --- the machines ---

    /** The sides with a machine touching them, in a fixed order. */
    public List<Direction> machineSides() {
        List<Direction> sides = new ArrayList<>();
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (hasMachine(side)) {
                    sides.add(side);
                }
            }
        }
        return sides;
    }

    /** Updates the port's shape to what touches each side now: a machine (idle or holding a job's items), the network, or nothing. */
    public void refreshSides() {
        if (!(level instanceof ServerLevel) || !(getBlockState().getBlock() instanceof AccessPortBlock)) {
            return;
        }
        BlockState state = getBlockState();
        BlockState shown = state;
        for (Direction side : Direction.values()) {
            shown = shown.setValue(AccessPortBlock.SIDES.get(side), sideLooks(side));
        }
        if (shown != state) {
            level.setBlock(worldPosition, shown, Block.UPDATE_CLIENTS);
        }
    }

    private PortSide sideLooks(Direction side) {
        if (hasMachine(side)) {
            return locks.containsKey(side) ? PortSide.BUSY : PortSide.MACHINE;
        }
        BlockPos pos = worldPosition.relative(side);
        BlockState there = level.getBlockState(pos);
        boolean hopperIn = there.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                && there.getValue(net.minecraft.world.level.block.HopperBlock.FACING) == side.getOpposite();
        boolean network = there.getBlock() instanceof DataCableBlock || level.getBlockEntity(pos) instanceof MachineBlockEntity
                || level.getBlockEntity(pos) instanceof ArchiveBlockEntity;
        return hopperIn || network ? PortSide.LINK : PortSide.NONE;
    }

    /**
     * Whether a machine touches {@code side}: a block that takes items, but not a hopper pointing into the port (that
     * one brings results back).
     */
    public boolean hasMachine(Direction side) {
        if (level == null) {
            return false;
        }
        BlockPos pos = worldPosition.relative(side);
        BlockState there = level.getBlockState(pos);
        if (there.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                && there.getValue(net.minecraft.world.level.block.HopperBlock.FACING) == side.getOpposite()) {
            return false;
        }
        return Machines.inlet(level, pos, side.getOpposite()) != null;
    }

    /**
     * The name of the machine on {@code side}: the block's own name, or the port's name if it has one (with the
     * block's after it when the port has more than one machine).
     */
    public Component machineName(Direction side) {
        Component block = Machines.blockName(level, worldPosition.relative(side));
        if (label.isEmpty()) {
            return block;
        }
        return machineSides().size() > 1 ? Component.literal(label + ": ").append(block) : Component.literal(label);
    }

    /** Every machine's name, for screens: "Furnace, Barrel", or a note that there is none. */
    public Component machineNames() {
        List<Direction> sides = machineSides();
        if (sides.isEmpty()) {
            return Component.translatable("screen.jasm.port.no_machine");
        }
        net.minecraft.network.chat.MutableComponent names = Component.empty();
        for (int i = 0; i < sides.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(Machines.blockName(level, worldPosition.relative(sides.get(i))));
        }
        return names;
    }

    // --- the locks ---

    public @Nullable Lock lock(Direction side) {
        return locks.get(side);
    }

    /** The jobs holding a side of this port. */
    public Set<UUID> lockedJobs() {
        Set<UUID> jobs = new LinkedHashSet<>();
        locks.values().forEach(l -> jobs.add(l.job()));
        return jobs;
    }

    public boolean locked() {
        return !locks.isEmpty();
    }

    /** Whether step {@code step} of job {@code job} may send sets to the machine on {@code side}: nobody else holds it. */
    public boolean freeFor(Direction side, UUID job, int step) {
        Lock lock = locks.get(side);
        return lock == null || lock.job().equals(job) && lock.step() == step;
    }

    public void lock(Direction side, UUID job, int step) {
        Lock wanted = new Lock(job, step);
        if (!wanted.equals(locks.put(side, wanted))) {
            setChanged();
            refreshSides();
        }
    }

    public void unlock(Direction side) {
        if (locks.remove(side) != null) {
            setChanged();
            refreshSides();
        }
    }

    /** Lets go of every side {@code job} holds. */
    public void unlockJob(UUID job) {
        if (locks.values().removeIf(l -> l.job().equals(job))) {
            setChanged();
            refreshSides();
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

    // --- the intake ---

    /** Takes up to {@code most} of {@code key} out of the intake. Returns how many. */
    long take(ItemResource key, long most) {
        long taken = 0;
        for (int i = 0; i < SLOTS && taken < most; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && ItemResource.of(stack).equals(key)) {
                int n = (int) Math.min(stack.getCount(), most - taken);
                stack.shrink(n);
                taken += n;
            }
        }
        if (taken > 0) {
            setChanged();
        }
        return taken;
    }

    /** What the intake holds, by item. */
    Map<ItemResource, Long> intake() {
        Map<ItemResource, Long> held = new java.util.LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                held.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return held;
    }

    /** Whether the port takes items in now: some side locked to a job, and powered. */
    public boolean open() {
        return !locks.isEmpty() && running();
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

    /** What the screen needs when it opens: where the port is, its name and its machines'. */
    public void writeOpening(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(worldPosition);
        buf.writeUtf(label, AccessPortMenu.MAX_NAME);
        net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, machineNames());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        List<SavedLock> saved = new ArrayList<>();
        locks.forEach((side, lock) -> saved.add(new SavedLock(side, lock.job(), lock.step())));
        output.store("locks", SavedLock.CODEC.listOf(), saved);
        output.putString("label", label);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        locks.clear();
        input.read("locks", SavedLock.CODEC.listOf()).orElse(List.of()).forEach(l -> locks.put(l.side(), new Lock(l.job(), l.step())));
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
