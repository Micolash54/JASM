package dev.micolash.jasm.network;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * A block of the crafting network: it belongs to the player who first placed it (mined and placed again, it still
 * does), holds some FE, and uses a flat amount every tick, busy or idle. Without enough for the tick it stops working
 * until charged again.
 */
public abstract class MachineBlockEntity extends BaseContainerBlockEntity {
    protected final SimpleEnergyHandler energy;
    private final int capacity;
    private @Nullable UUID owner;
    private String ownerName = "";
    /** Whether the last tick could be paid for. */
    private boolean running;

    protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int capacity) {
        super(type, pos, state);
        this.capacity = capacity;
        this.energy = new SimpleEnergyHandler(capacity, capacity, capacity) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
    }

    /** FE this block uses each tick. */
    public abstract int drainPerTick();

    /** Pays for one tick. Returns whether the block works this tick. */
    public boolean payForTick() {
        if (level instanceof ServerLevel serverLevel) {
            // Asking for the network keeps it alive, so it passes power on to this block each tick.
            Networks.at(serverLevel, worldPosition);
        }
        int drain = drainPerTick();
        int amount = energy.getAmountAsInt();
        running = amount >= drain;
        if (running && drain > 0) {
            energy.set(amount - drain);
        }
        return running;
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

    public boolean isOwner(Player player) {
        return owner == null || owner.equals(player.getUUID());
    }

    /** Set once, by whoever places the block. */
    public void setOwner(Player player) {
        owner = player.getUUID();
        ownerName = player.getPlainTextName();
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy.getAmountAsInt());
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.putString("owner_name", ownerName);
        output.putBoolean("running", running);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, capacity));
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
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
