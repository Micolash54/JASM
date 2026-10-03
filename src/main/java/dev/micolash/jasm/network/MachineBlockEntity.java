package dev.micolash.jasm.network;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * A block of the crafting network: it adopts the network's owner, holds some FE, and uses a flat amount every tick,
 * busy or idle. Without enough for the tick it stops working
 * until charged again.
 */
public abstract class MachineBlockEntity extends BaseContainerBlockEntity {
    protected final SimpleEnergyHandler energy;
    private final int capacity;
    private @Nullable UUID owner;
    private String ownerName = "";
    private boolean networkBlocked;
    /** Whether the last tick could be paid for. */
    private boolean running;
    /** Whether the network is over its machine limit: the block works as if it had no power, but keeps charging. */
    private boolean stopped;

    protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int capacity) {
        super(type, pos, state);
        this.capacity = capacity;
        this.energy = new NetworkEnergy(capacity) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
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

    /** Pays for one tick. Returns whether the block works this tick: not while its network is full. */
    public boolean payForTick() {
        if (checkStopped()) {
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
        return capacity;
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
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, capacity));
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        networkBlocked = input.getBooleanOr("network_blocked", false);
        running = input.getBooleanOr("running", false);
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
        energy.set(Math.clamp(components.getOrDefault(JasmComponents.ENERGY.get(), 0), 0, capacity));
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
