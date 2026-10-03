package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.BrainCube;
import dev.micolash.jasm.core.BrainLevels;
import dev.micolash.jasm.core.BrainProgress;
import dev.micolash.jasm.core.BrainSize;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
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
import org.jspecify.annotations.Nullable;

/**
 * The Network Brain. While it has power and leads its network it learns a little every tick, up to the top level its
 * size allows, and eats the typed chips in its slot to learn faster. When a network holds several brains only the best
 * working one leads; the others rest. Mined, it keeps its level and points.
 */
public class NetworkBrainBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOT = 0;
    public static final int SLOTS = 1;
    public static final int CAPACITY = 100_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private BrainProgress progress = BrainProgress.START;
    private BrainSize size = BrainSize.SINGLE;
    /** The cube of chambers it sits in, in world coordinates; null on its own. */
    private BrainCube.@Nullable Box box;
    /** Whether it has looked for its cube since it was loaded or placed. */
    private boolean shapeChecked;
    /** Being removed: it lets its chambers go and takes none back. */
    private boolean leaving;
    private BrainStatus status = BrainStatus.NO_POWER;
    /** Game time of its first tick; the older brain wins a tie. */
    private long placedAt = -1;
    /** Ticks since it last ate a chip. */
    private int sinceEat;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            BrainBalance balance = BrainBalance.fromConfig();
            return switch (index) {
                case NetworkBrainMenu.DATA_LEVEL -> BrainLevels.shownLevel(progress, size, balance);
                case NetworkBrainMenu.DATA_PERCENT -> BrainLevels.percent(progress, size, balance);
                case NetworkBrainMenu.DATA_COUNT -> {
                    CableNetwork network = network();
                    yield network == null ? 0 : network.limitState().count();
                }
                case NetworkBrainMenu.DATA_LIMIT -> {
                    CableNetwork network = network();
                    yield network == null ? BrainLevels.machineLimit(BrainLevels.shownLevel(progress, size, balance), balance)
                            : network.limitState().limit();
                }
                case NetworkBrainMenu.DATA_SIZE -> size.ordinal();
                case NetworkBrainMenu.DATA_STATUS -> status.ordinal();
                case NetworkBrainMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case NetworkBrainMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return NetworkBrainMenu.DATA_SLOTS;
        }
    };

    public NetworkBrainBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.NETWORK_BRAIN_ENTITY.get(), pos, state, CAPACITY);
    }

    // --- the tick ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, NetworkBrainBlockEntity brain) {
        brain.tick((ServerLevel) level, state);
    }

    private void tick(ServerLevel level, BlockState state) {
        if (!shapeChecked) {
            shapeChecked = true;
            BrainShapes.reshape(level, this);
        }
        if (placedAt < 0) {
            placedAt = level.getGameTime();
            setChanged();
        }
        // Asking for the network keeps it alive, so it passes power on to this block each tick.
        boolean powered = payForTick();
        CableNetwork network = Networks.at(level, worldPosition);
        boolean leading = network == null || network.leader() == this;
        BrainBalance balance = BrainBalance.fromConfig();
        BrainStatus next;
        if (!powered) {
            next = BrainStatus.NO_POWER;
        } else if (!leading) {
            next = BrainStatus.RESTING;
        } else if (BrainLevels.shownLevel(progress, size, balance) == BrainBalance.MAX_LEVEL) {
            next = BrainStatus.FULLY_GROWN;
        } else if (BrainLevels.capped(progress, size, balance)) {
            next = BrainStatus.NEEDS_BIGGER;
        } else {
            next = BrainStatus.LEARNING;
        }
        if (next != status) {
            status = next;
            setChanged();
        }
        if (status == BrainStatus.LEARNING) {
            setProgress(BrainLevels.add(progress, BrainLevels.passive(size, balance), size, balance));
            if (++sinceEat >= balance.eatEvery()) {
                sinceEat = 0;
                ItemStack chip = items.get(SLOT);
                // The passive points may have just reached the cap; a capped brain never eats.
                if (chip.is(JasmTags.TYPED_CHIPS) && !BrainLevels.capped(progress, size, balance)) {
                    boolean advanced = chip.is(JasmTags.ADVANCED_CHIPS);
                    chip.shrink(1);
                    setProgress(BrainLevels.add(progress, BrainLevels.chipPoints(progress, advanced, balance), size, balance));
                }
            }
        }
        boolean awake = status != BrainStatus.NO_POWER && status != BrainStatus.RESTING;
        // Read again: forming the cube may have changed the block's size this tick.
        BlockState now = level.getBlockState(worldPosition);
        if (now.hasProperty(NetworkBrainBlock.AWAKE) && now.getValue(NetworkBrainBlock.AWAKE) != awake) {
            level.setBlock(worldPosition, now.setValue(NetworkBrainBlock.AWAKE, awake), Block.UPDATE_CLIENTS);
        }
    }

    // --- what the brain knows ---

    public BrainProgress progress() {
        return progress;
    }

    public void setProgress(BrainProgress progress) {
        this.progress = progress;
        setChanged();
    }

    public BrainSize size() {
        return size;
    }

    boolean leaving() {
        return leaving;
    }

    /** The cube of chambers it sits in, in world coordinates; null on its own. */
    public BrainCube.@Nullable Box box() {
        return box;
    }

    /** Sets the cube it sits in, and with it its size, on the block too. */
    public void setBox(BrainCube.@Nullable Box box) {
        this.box = box;
        size = box == null ? BrainSize.SINGLE : box.size();
        // While the brain is being removed its spot already holds the new block; leave that alone.
        if (level != null && !level.isClientSide()) {
            BlockState state = level.getBlockState(worldPosition);
            if (state.hasProperty(NetworkBrainBlock.SIZE) && state.getValue(NetworkBrainBlock.SIZE) != size) {
                level.setBlock(worldPosition, state.setValue(NetworkBrainBlock.SIZE, size), Block.UPDATE_ALL);
            } else if (state.hasProperty(NetworkBrainBlock.SIZE)) {
                // Same size, other cube: players still need to hear where it is.
                level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            }
        }
        setChanged();
    }

    /** The level that counts: held down to the cap of the brain's size. */
    public int shownLevel() {
        return BrainLevels.shownLevel(progress, size, BrainBalance.fromConfig());
    }

    /** Whether the last tick was paid for. */
    public boolean working() {
        return running();
    }

    public BrainStatus status() {
        return status;
    }

    public long placedAt() {
        return placedAt;
    }

    private @Nullable CableNetwork network() {
        return level instanceof ServerLevel serverLevel ? Networks.at(serverLevel, worldPosition) : null;
    }

    @Override
    public int drainPerTick() {
        return BrainLevels.drain(size, BrainBalance.fromConfig());
    }

    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    /** Its chambers are let go before it leaves, and a brain nearby may take them up. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            leaving = true;
            BrainShapes.release(serverLevel, this);
        }
        super.preRemoveSideEffects(pos, state);
    }

    // --- container ---

    /** Typed chips, while the brain can still learn from them. */
    public boolean accepts(ItemStack stack) {
        return stack.is(JasmTags.TYPED_CHIPS) && !BrainLevels.capped(progress, size, BrainBalance.fromConfig());
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == SLOT && accepts(stack);
    }

    /** Hoppers and pipes feed chips in from any side; nothing comes back out. */
    @Override
    public int[] getSlotsForFace(Direction direction) {
        return new int[] {SLOT};
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
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
        return Component.translatable("block.jasm.network_brain");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new NetworkBrainMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition), getBlockState().getBlock());
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.store("progress", BrainProgress.CODEC, progress);
        output.putLong("placed_at", placedAt);
        if (box != null) {
            output.putInt("box_x", box.x());
            output.putInt("box_y", box.y());
            output.putInt("box_z", box.z());
            output.putInt("box_side", box.side());
        }
        output.putInt("status", status.ordinal());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        progress = input.read("progress", BrainProgress.CODEC).orElse(BrainProgress.START);
        placedAt = input.getLongOr("placed_at", -1);
        readBox(input);
        status = byOrdinal(BrainStatus.values(), input.getIntOr("status", -1), BrainStatus.NO_POWER);
    }

    // Players only get the cube, so the brain can be drawn filling it.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (box != null) {
            tag.putInt("box_x", box.x());
            tag.putInt("box_y", box.y());
            tag.putInt("box_z", box.z());
            tag.putInt("box_side", box.side());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        readBox(input);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        readBox(input);
    }

    private void readBox(ValueInput input) {
        int side = input.getIntOr("box_side", 0);
        box = side < 2 ? null : new BrainCube.Box(input.getIntOr("box_x", 0), input.getIntOr("box_y", 0), input.getIntOr("box_z", 0), side);
        size = box == null ? BrainSize.SINGLE : box.size();
    }

    private static <E> E byOrdinal(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    /** The mined item keeps the brain's level and points. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(JasmComponents.BRAIN.get(), progress);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        progress = components.getOrDefault(JasmComponents.BRAIN.get(), BrainProgress.START);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("progress");
    }
}
