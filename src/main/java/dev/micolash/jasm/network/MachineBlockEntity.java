package dev.micolash.jasm.network;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * A block of the crafting network: it adopts the network's owner, holds some FE, and uses a flat amount every tick,
 * busy or idle. Without enough for the tick it stops working
 * until charged again.
 */
public abstract class MachineBlockEntity extends BaseContainerBlockEntity {
    private static final int PUSH_TICKS = 10;
    private static final int PUSH_ITEMS = 64;
    protected final NetworkEnergy energy;
    private @Nullable UUID owner;
    private String ownerName = "";
    private boolean networkBlocked;
    /** Whether the last tick could be paid for. */
    private boolean running;
    /** Whether the network is over its machine limit: the block works as if it had no power, but keeps charging. */
    private boolean stopped;

    protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int capacity) {
        super(type, pos, state);
        this.energy = new NetworkEnergy(capacity) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                // power never changes a comparator signal, so only saving needs to hear about it
                if (level != null) level.blockEntityChanged(worldPosition);
            }
        };
    }

    /** What players were last told the block looks like, packed by the block; -1 until the first tick. */
    private int sentLooks = -1;

    /** FE this block uses each tick. */
    public abstract int drainPerTick();

    /** Whether this block takes up one of its network's machine places. Brains and chambers don't. */
    public boolean countsTowardLimit() {
        return true;
    }

    /** Whether ports, hoppers and Access Ports may reach this block's own slots: only machines with an I/O grid, as it allows. */
    public boolean opensToPorts() {
        return sides() != null;
    }

    /** The I/O grid, for the machines that have one. */
    public @Nullable MachineSides sides() {
        return null;
    }

    /** The machine's item slots as automation sees them, before the grid has its say; null without a grid. */
    protected MachineSides.@Nullable Gated<ItemResource> itemGates() {
        return null;
    }

    /** The same for its tank; null for machines without one. */
    protected MachineSides.@Nullable Gated<FluidResource> fluidGates() {
        return null;
    }

    /** Which way the machine's front looks. */
    public Direction frontSide() {
        BlockState state = getBlockState();
        return state.hasProperty(MachineBlock.FACING) ? state.getValue(MachineBlock.FACING) : Direction.NORTH;
    }

    /** What hoppers, pipes and ports reach through {@code side}: as much as that face's mode allows, or nothing. */
    public @Nullable ResourceHandler<ItemResource> itemsThrough(@Nullable Direction side) {
        MachineSides sides = sides();
        MachineSides.Gated<ItemResource> gates = itemGates();
        if (side == null || sides == null || gates == null) return null;
        return gates.through(sides.mode(MachineSides.Kind.ITEMS, MachineFace.of(frontSide(), side)));
    }

    public @Nullable ResourceHandler<FluidResource> fluidsThrough(@Nullable Direction side) {
        MachineSides sides = sides();
        MachineSides.Gated<FluidResource> gates = fluidGates();
        if (side == null || sides == null || gates == null) return null;
        return gates.through(sides.mode(MachineSides.Kind.FLUIDS, MachineFace.of(frontSide(), side)));
    }

    /** A player changed the grid: what neighbours reach through each face changed with it. */
    protected void sidesChanged() {
        setChanged();
        if (level != null) level.invalidateCapabilities(worldPosition);
    }

    /**
     * Twice a second, sends what the machine made out of each Output face into the block there, up to a stack (or a
     * bucket) a face. Neighbours that aren't loaded are skipped.
     */
    protected void pushOutputs(ServerLevel level) {
        MachineSides sides = sides();
        if (sides == null || !sides.anyOut() || (level.getGameTime() + worldPosition.asLong()) % PUSH_TICKS != 0) return;
        MachineSides.Gated<ItemResource> items = itemGates();
        MachineSides.Gated<FluidResource> fluids = fluidGates();
        Direction front = frontSide();
        for (MachineFace face : MachineFace.values()) {
            boolean pushItems = items != null && sides.mode(MachineSides.Kind.ITEMS, face).out();
            boolean pushFluids = fluids != null && sides.hasFluids() && sides.mode(MachineSides.Kind.FLUIDS, face).out();
            if (!pushItems && !pushFluids) continue;
            Direction side = face.toWorld(front);
            BlockPos next = worldPosition.relative(side);
            if (!level.isLoaded(next)) continue;
            if (pushItems) {
                ResourceHandlerUtil.move(items.through(FaceMode.OUTPUT), level.getCapability(Capabilities.Item.BLOCK, next, side.getOpposite()),
                        resource -> true, PUSH_ITEMS, null);
            }
            if (pushFluids) {
                ResourceHandlerUtil.move(fluids.through(FaceMode.OUTPUT), level.getCapability(Capabilities.Fluid.BLOCK, next, side.getOpposite()),
                        resource -> true, FluidAmounts.PER_BUCKET, null);
            }
        }
    }

    public boolean stopped() {
        return stopped;
    }

    /**
     * Looks the network up and notes whether it is over its limit. Asking for the network keeps it alive, so it passes
     * power on to this block each tick.
     */
    protected boolean checkStopped() {
        boolean now = false;
        if (level instanceof ServerLevel serverLevel) {
            CableNetwork network = Networks.at(serverLevel, worldPosition);
            now = countsTowardLimit() && network != null && network.limitState().stopped();
        }
        stopped = now;
        return now;
    }

    // false to sit the tick out without paying, like a brain charging back up
    protected boolean readyToRun() {
        return true;
    }

    /** Pays for one tick. Returns whether the block works this tick: not while its network is full. */
    public boolean payForTick() {
        if (checkStopped() || !readyToRun()) {
            running = false;
            return false;
        }
        int drain = drainPerTick();
        int amount = energy.getAmountAsInt();
        running = amount >= drain;
        if (running && drain > 0) {
            energy.set(amount - drain);
        }
        return running;
    }

    /** Whether {@code looks} differs from what players were last told; it counts as told from here on. */
    protected final boolean looksChanged(int looks) {
        if (looks == sentLooks) {
            return false;
        }
        sentLooks = looks;
        return true;
    }

    /** Tells players the block looks different, if {@code looks} isn't what they last heard. */
    protected final void syncLooks(int looks) {
        if (looksChanged(looks) && level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Whether the block is working: the last tick was paid for. */
    public boolean running() {
        return running;
    }

    public SimpleEnergyHandler energy() {
        return energy;
    }

    public int capacity() {
        return energy.getCapacityAsInt();
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean networkBlocked() {
        return networkBlocked;
    }

    public void setNetworkBlocked(boolean blocked) {
        if (networkBlocked != blocked) {
            networkBlocked = blocked;
            setChanged();
        }
    }

    public boolean isOwner(Player player) {
        return owner == null || owner.equals(player.getUUID());
    }

    /** Set by placement or by the network this block joins. */
    public void setOwner(Player player) {
        adoptOwner(player.getUUID(), player.getPlainTextName());
    }

    public void adoptOwner(UUID id, String name) {
        if (id.equals(owner)) {
            return;
        }
        UUID previous = owner;
        owner = id;
        ownerName = name;
        onOwnerChanged(previous);
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            CableClaims.get(serverLevel).rememberOwner(id, name);
            Networks.ownerChanged(serverLevel, worldPosition);
        }
    }

    protected void onOwnerChanged(@Nullable UUID previous) {
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy.getAmountAsInt());
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.putString("owner_name", ownerName);
        output.putBoolean("network_blocked", networkBlocked);
        output.putBoolean("running", running);
        MachineSides sides = sides();
        if (sides != null) sides.save(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, energy.getCapacityAsInt()));
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        networkBlocked = input.getBooleanOr("network_blocked", false);
        running = input.getBooleanOr("running", false);
        MachineSides sides = sides();
        if (sides != null) sides.load(input);
    }

    /** The mined item keeps the charge and the owner. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (energy.getAmountAsInt() > 0) {
            components.set(JasmComponents.ENERGY.get(), energy.getAmountAsInt());
        }
        if (owner != null) {
            components.set(JasmComponents.OWNER.get(), new MachineOwner(owner, ownerName));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        energy.set(Math.clamp(components.getOrDefault(JasmComponents.ENERGY.get(), 0), 0, energy.getCapacityAsInt()));
        MachineOwner carried = components.get(JasmComponents.OWNER.get());
        if (carried != null) {
            owner = carried.id();
            ownerName = carried.name();
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("energy");
        output.discard("owner");
        output.discard("owner_name");
    }
}
