package dev.micolash.jasm.network;

import dev.micolash.jasm.config.JasmConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * One crafting network: every Data Cable, machine and Archive joined face to face. The cables hold a little power
 * between them; each tick the network hands it on to its machines, then to anything else touching a cable that takes
 * FE (an Archive among them).
 */
public final class CableNetwork {
    private final ServerLevel level;
    private final Set<BlockPos> cables;
    private final Set<BlockPos> machines;
    private final SimpleEnergyHandler buffer;
    /** Blocks outside the network that touch a cable and may take FE, found when the network was built. */
    private final List<BlockCapabilityCache<EnergyHandler, @Nullable Direction>> outlets = new ArrayList<>();
    private long lastTick = -1;

    CableNetwork(ServerLevel level, Set<BlockPos> cables, Set<BlockPos> machines, int energy) {
        this.level = level;
        this.cables = Collections.unmodifiableSet(cables);
        this.machines = Collections.unmodifiableSet(machines);
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) cables.size()) * JasmConfig.CABLE_BUFFER.getAsInt());
        this.buffer = new SimpleEnergyHandler(capacity, capacity, capacity);
        this.buffer.set(Math.clamp(energy, 0, capacity));
        for (BlockPos cable : cables) {
            for (Direction side : Direction.values()) {
                BlockPos next = cable.relative(side);
                // Cables of another colour are another network: power doesn't leak across. Machines get theirs above.
                boolean machine = machines.contains(next) && level.getBlockEntity(next) instanceof MachineBlockEntity;
                if (!cables.contains(next) && !machine && !(level.getBlockState(next).getBlock() instanceof DataCableBlock)) {
                    outlets.add(BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, next, side.getOpposite()));
                }
            }
        }
    }

    public Set<BlockPos> cables() {
        return cables;
    }

    /** Positions of the blocks on this network that aren't cables: machines and Archives. */
    public Set<BlockPos> machines() {
        return machines;
    }

    /** The machines on this network that are loaded, of the given kind. */
    public <T extends MachineBlockEntity> List<T> machines(Class<T> kind) {
        List<T> found = new ArrayList<>();
        for (BlockPos pos : machines) {
            if (level.isLoaded(pos) && kind.isInstance(level.getBlockEntity(pos))) {
                found.add(kind.cast(level.getBlockEntity(pos)));
            }
        }
        return found;
    }

    public boolean contains(BlockPos pos) {
        return cables.contains(pos) || machines.contains(pos);
    }

    public SimpleEnergyHandler buffer() {
        return buffer;
    }

    /** Moves power along, once per game tick however often it is asked. */
    void tick() {
        long now = level.getGameTime();
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        int rate = JasmConfig.CABLE_RATE.getAsInt();
        // Machines share what the cables hold, each an equal part (up to the rate); whoever starts goes round.
        List<MachineBlockEntity> needy = new ArrayList<>();
        for (MachineBlockEntity machine : machines(MachineBlockEntity.class)) {
            if (machine.energy().getAmountAsInt() < machine.capacity()) {
                needy.add(machine);
            }
        }
        if (!needy.isEmpty()) {
            int share = Math.max(1, Math.min(rate, buffer.getAmountAsInt() / needy.size()));
            int start = (int) Math.floorMod(now, (long) needy.size());
            for (int i = 0; i < needy.size() && buffer.getAmountAsInt() > 0; i++) {
                move(needy.get((start + i) % needy.size()).energy(), share);
            }
        }
        for (BlockCapabilityCache<EnergyHandler, @Nullable Direction> outlet : outlets) {
            if (buffer.getAmountAsInt() <= 0) {
                return;
            }
            EnergyHandler target = outlet.getCapability();
            if (target != null) {
                move(target, rate);
            }
        }
    }

    private void move(EnergyHandler target, int limit) {
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(Math.min(limit, buffer.getAmountAsInt()), tx);
            if (accepted > 0 && buffer.extract(accepted, tx) == accepted) {
                tx.commit();
            }
        }
    }
}
