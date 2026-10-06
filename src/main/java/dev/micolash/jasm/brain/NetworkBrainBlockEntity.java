package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * The Network Brain. While it has power it lets its network hold more machines. With 8 chambers round it, it is a floor;
 * floors stacked straight on top of each other make a tower, and each floor raises the limit. The lowest floor speaks for
 * its tower; the brains above it show what it shows. When a network holds several brains or towers, only the best
 * working one leads; the others rest.
 */
public class NetworkBrainBlockEntity extends MachineBlockEntity {
    public static final int CAPACITY = 100_000;
    // after running dry it sleeps until it has this much, a weak cable made it flicker on and off every tick
    public static final int WAKE_AT = CAPACITY / 20;

    /** The middle of a complete floor. */
    private boolean floor;
    /** Whether it has looked for its floor and tower since it was loaded or placed. */
    private boolean shapeChecked;
    /** Being removed: it lets its chambers go and takes none back. */
    private boolean leaving;
    /**
     * Its tower's lowest brain, itself when it is the lowest or not a floor. Saved, so the network's limit is right before
     * the first tick after loading; that tick works it out again.
     */
    private BlockPos towerBase;
    /** Floors in its tower; 0 for a brain that isn't a floor. */
    private int towerFloors;
    /** The config's stamp when the tower was worked out, so a new height limit restacks it. */
    private int towerStamp = -1;
    private @Nullable NetworkBrainBlockEntity base;
    private BrainStatus status = BrainStatus.NO_POWER;
    /** Game time of its first tick; the older brain wins a tie. */
    private long placedAt = -1;
    private boolean napping;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case NetworkBrainMenu.DATA_FLOORS -> towerFloors;
                case NetworkBrainMenu.DATA_COUNT -> {
                    CableNetwork network = network();
                    yield network == null ? 0 : network.limitState().count();
                }
                case NetworkBrainMenu.DATA_LIMIT -> {
                    CableNetwork network = network();
                    yield network == null ? BrainBalance.fromConfig().limit(true, towerFloors) : network.limitState().limit();
                }
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
        towerBase = pos;
    }

    // --- the tick ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, NetworkBrainBlockEntity brain) {
        brain.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        if (!shapeChecked) {
            shapeChecked = true;
            BrainShapes.reshape(level, this);
        } else if (towerStamp != BrainBalance.stamp()) {
            BrainShapes.restack(level, worldPosition);
        }
        if (placedAt < 0) {
            placedAt = level.getGameTime();
            setChanged();
        }
        // Asking for the network keeps it alive, so it passes power on to this block each tick.
        boolean powered = payForTick();
        CableNetwork network = Networks.at(level, worldPosition);
        NetworkBrainBlockEntity lowest = base();
        BrainStatus next;
        if (lowest != this) {
            next = lowest == null ? BrainStatus.NO_POWER : lowest.status;
        } else if (!powered) {
            next = BrainStatus.NO_POWER;
        } else if (network != null && network.leader() != this) {
            next = BrainStatus.RESTING;
        } else {
            next = BrainStatus.WORKING;
        }
        if (next != status) {
            status = next;
            setChanged();
        }
        boolean awake = status == BrainStatus.WORKING;
        BlockState now = level.getBlockState(worldPosition);
        if (now.hasProperty(NetworkBrainBlock.AWAKE) && now.getValue(NetworkBrainBlock.AWAKE) != awake) {
            level.setBlock(worldPosition, now.setValue(NetworkBrainBlock.AWAKE, awake), Block.UPDATE_CLIENTS);
        }
    }

    // --- what the brain knows ---

    public boolean floor() {
        return floor;
    }

    /** Becomes the middle of a floor, or stops being one, on the block too. */
    void setFloor(boolean floor) {
        this.floor = floor;
        // While the brain is being removed its spot already holds the new block; leave that alone.
        if (level != null && !level.isClientSide()) {
            BlockState state = level.getBlockState(worldPosition);
            if (state.hasProperty(NetworkBrainBlock.FLOOR) && state.getValue(NetworkBrainBlock.FLOOR) != floor) {
                level.setBlock(worldPosition, state.setValue(NetworkBrainBlock.FLOOR, floor), Block.UPDATE_ALL);
            }
        }
        setChanged();
    }

    void setTower(BlockPos base, int floors, int stamp) {
        towerBase = base.immutable();
        towerFloors = floors;
        towerStamp = stamp;
        this.base = null;
    }

    boolean leaving() {
        return leaving;
    }

    /** Whether it speaks for its tower: the lowest floor, or a brain that isn't a floor. */
    public boolean leadsItsTower() {
        return towerBase.equals(worldPosition);
    }

    /** Floors in its tower; 0 for a brain that isn't a floor. */
    public int floors() {
        return towerFloors;
    }

    /** Its tower's lowest brain, or null while that isn't loaded. */
    private @Nullable NetworkBrainBlockEntity base() {
        if (leadsItsTower()) {
            return this;
        }
        if ((base == null || base.isRemoved()) && level != null && level.isLoaded(towerBase)) {
            base = level.getBlockEntity(towerBase) instanceof NetworkBrainBlockEntity found ? found : null;
        }
        return base;
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
        return BrainBalance.fromConfig().drainPerFloor();
    }

    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    protected boolean readyToRun() {
        int amount = energy.getAmountAsInt();
        if (amount < drainPerTick()) {
            napping = true;
        } else if (napping && amount >= WAKE_AT) {
            napping = false;
        }
        return !napping;
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

    // --- container: it holds nothing ---

    @Override
    protected NonNullList<ItemStack> getItems() {
        return NonNullList.create();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
    }

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.jasm.network_brain");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new NetworkBrainMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition),
                getBlockState().getBlock());
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("floor", floor);
        output.putLong("tower_base", towerBase.asLong());
        output.putInt("tower_floors", towerFloors);
        output.putLong("placed_at", placedAt);
        output.putInt("status", status.ordinal());
        output.putBoolean("napping", napping);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        floor = input.getBooleanOr("floor", false);
        towerBase = BlockPos.of(input.getLongOr("tower_base", worldPosition.asLong()));
        towerFloors = floor ? Math.max(0, input.getIntOr("tower_floors", 0)) : 0;
        base = null;
        placedAt = input.getLongOr("placed_at", -1);
        int ordinal = input.getIntOr("status", -1);
        BrainStatus[] statuses = BrainStatus.values();
        status = ordinal >= 0 && ordinal < statuses.length ? statuses[ordinal] : BrainStatus.NO_POWER;
        napping = input.getBooleanOr("napping", false);
    }
}
