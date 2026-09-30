package dev.micolash.jasm.network;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * One crafting network: Data Cables, machines, Archives and power sources joined face to face. Touching machines
 * share their charge; cables carry it between separated blocks at their configured rate.
 */
public final class CableNetwork {
    private final ServerLevel level;
    private final Set<BlockPos> cables;
    private final Set<BlockPos> machines;
    private final boolean complete;
    private final SimpleEnergyHandler buffer;
    private long lastTick = -1;

    CableNetwork(ServerLevel level, Set<BlockPos> cables, Set<BlockPos> machines, int energy, boolean complete) {
        this.level = level;
        this.cables = Collections.unmodifiableSet(cables);
        this.machines = Collections.unmodifiableSet(machines);
        this.complete = complete;
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) cables.size()) * JasmConfig.CABLE_BUFFER.getAsInt());
        this.buffer = new SimpleEnergyHandler(capacity, capacity, capacity);
        this.buffer.set(Math.clamp(energy, 0, capacity));
    }

    public Set<BlockPos> cables() {
        return cables;
    }

    /** False when the walk stopped at an unloaded chunk. */
    public boolean complete() {
        return complete;
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
        for (BlockPos pos : cables) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
                for (var port : cable.ports()) if (kind.isInstance(port)) found.add(kind.cast(port));
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
        shareAdjacentPower();
        SimpleEnergyHandler leastCharged = null;
        for (BlockPos pos : machines) {
            SimpleEnergyHandler candidate = energyAt(pos);
            if (candidate != null && (leastCharged == null
                    || (long) candidate.getAmountAsInt() * leastCharged.getCapacityAsLong()
                        < (long) leastCharged.getAmountAsInt() * candidate.getCapacityAsLong())) {
                leastCharged = candidate;
            }
        }
        // A machine supplied directly can feed the cables too. Its own running cost stays in its buffer.
        for (BlockPos pos : machines) {
            SimpleEnergyHandler source = energyAt(pos);
            if (source == null || !touchesCable(pos) || source.getAmountAsInt() <= reserveAt(pos)) {
                continue;
            }
            boolean needed = leastCharged != null && leastCharged != source
                    && (long) leastCharged.getAmountAsInt() * source.getCapacityAsLong()
                        < (long) source.getAmountAsInt() * leastCharged.getCapacityAsLong();
            if (needed) {
                transfer(source, buffer, Math.min(rate, source.getAmountAsInt() - reserveAt(pos)));
            }
        }
        // Machines share what the cables hold, each an equal part (up to the rate); whoever starts goes round.
        List<SimpleEnergyHandler> needy = new ArrayList<>();
        for (BlockPos pos : machines) {
            SimpleEnergyHandler target = energyAt(pos);
            if (target != null && target.getAmountAsInt() < target.getCapacityAsLong()) {
                needy.add(target);
            }
        }
        for (BlockPos pos : cables) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
                for (var port : cable.ports()) if (port.energy().getAmountAsInt() < port.capacity()) needy.add(port.energy());
            }
        }
        if (!needy.isEmpty()) {
            int share = Math.max(1, Math.min(rate, buffer.getAmountAsInt() / needy.size()));
            int start = (int) Math.floorMod(now, (long) needy.size());
            for (int i = 0; i < needy.size() && buffer.getAmountAsInt() > 0; i++) {
                move(needy.get((start + i) % needy.size()), share);
            }
        }
        shareAdjacentPower();
    }

    private void move(EnergyHandler target, int limit) {
        transfer(buffer, target, limit);
    }

    private boolean touchesCable(BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (cables.contains(pos.relative(side))) {
                return true;
            }
        }
        return false;
    }

    private @Nullable SimpleEnergyHandler energyAt(BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            return machine.energy();
        }
        return level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive ? archive.energy() : null;
    }

    private int reserveAt(BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            return machine.drainPerTick();
        }
        return level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive ? archive.tier().drainPerTick() : 0;
    }

    private void shareAdjacentPower() {
        for (BlockPos pos : machines) {
            SimpleEnergyHandler a = energyAt(pos);
            if (a == null) {
                continue;
            }
            for (Direction side : List.of(Direction.EAST, Direction.UP, Direction.SOUTH)) {
                BlockPos next = pos.relative(side);
                SimpleEnergyHandler b = machines.contains(next) ? energyAt(next) : null;
                if (b == null) {
                    continue;
                }
                long capacity = a.getCapacityAsLong() + b.getCapacityAsLong();
                if (capacity == 0) {
                    continue;
                }
                long total = (long) a.getAmountAsInt() + b.getAmountAsInt();
                int wanted = (int) (total * a.getCapacityAsLong() / capacity);
                int difference = a.getAmountAsInt() - wanted;
                if (difference > 0) {
                    transfer(a, b, Math.min(difference, Math.max(0, a.getAmountAsInt() - reserveAt(pos))));
                } else if (difference < 0) {
                    transfer(b, a, Math.min(-difference, Math.max(0, b.getAmountAsInt() - reserveAt(next))));
                }
            }
        }
    }

    private static void transfer(EnergyHandler source, EnergyHandler target, int limit) {
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(Math.min(limit, source.getAmountAsInt()), tx);
            if (accepted > 0 && source.extract(accepted, tx) == accepted) {
                tx.commit();
            }
        }
    }
}
