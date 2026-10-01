package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * One crafting network: Data Cables, machines and Archives joined face to face. Touching machines share their charge.
 * Every cable holds its own power and passes it on to its neighbours, each step at the slower of the two cables' rates,
 * so a fast cable feeds the blocks it touches at its own speed even when slower cables join the same network.
 */
public final class CableNetwork {
    private final ServerLevel level;
    private final Set<BlockPos> cables;
    private final Set<BlockPos> machines;
    private final boolean complete;
    private long lastTick = -1;

    CableNetwork(ServerLevel level, Set<BlockPos> cables, Set<BlockPos> machines, boolean complete) {
        this.level = level;
        this.cables = Collections.unmodifiableSet(cables);
        this.machines = Collections.unmodifiableSet(machines);
        this.complete = complete;
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

    /** All the power the cables hold right now. */
    public long stored() {
        long total = 0;
        for (BlockPos pos : cables) {
            DataCableBlockEntity cable = cableAt(pos);
            if (cable != null) total += cable.energy().getAmountAsLong();
        }
        return total;
    }

    /**
     * Moves power along, once per game tick however often it is asked. Nearly full machines with spare charge feed the
     * cables they touch when another machine needs it more. Then each cable next to a machine or port that wants power
     * pulls it from the nearest cables that hold some and hands it over, so power crosses a long line in one tick.
     */
    void tick() {
        long now = level.getGameTime();
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        shareAdjacentPower();
        if (cables.isEmpty()) {
            return;
        }
        Map<BlockPos, DataCableBlockEntity> loaded = new HashMap<>();
        for (BlockPos pos : cables) {
            DataCableBlockEntity cable = cableAt(pos);
            if (cable != null) loaded.put(pos, cable);
        }
        SimpleEnergyHandler leastCharged = null;
        for (BlockPos pos : machines) {
            SimpleEnergyHandler candidate = energyAt(pos);
            if (candidate != null && candidate.getAmountAsInt() < candidate.getCapacityAsLong() && (leastCharged == null
                    || (long) candidate.getAmountAsInt() * leastCharged.getCapacityAsLong()
                        < (long) leastCharged.getAmountAsInt() * candidate.getCapacityAsLong())) {
                leastCharged = candidate;
            }
        }
        // A nearly full machine, supplied directly, can feed the cables too. Its own running cost stays in its buffer.
        // Below that it keeps what it gets, so power a cable just delivered isn't handed straight back.
        Set<SimpleEnergyHandler> sources = new HashSet<>();
        for (BlockPos pos : machines) {
            SimpleEnergyHandler source = energyAt(pos);
            if (source == null || source.getAmountAsInt() <= reserveAt(pos) || leastCharged == null || leastCharged == source
                    || source.getAmountAsLong() * 10 < source.getCapacityAsLong() * 9
                    || (long) leastCharged.getAmountAsInt() * source.getCapacityAsLong()
                        >= (long) source.getAmountAsInt() * leastCharged.getCapacityAsLong()) {
                continue;
            }
            for (Direction side : Direction.values()) {
                DataCableBlockEntity cable = loaded.get(pos.relative(side));
                if (cable != null && cable.port(side.getOpposite()) == null) {
                    sources.add(source);
                    transfer(source, cable.energy(), Math.min(cable.tier().rate(), source.getAmountAsInt() - reserveAt(pos)));
                }
            }
        }
        // Where power is wanted: cables next to a machine that isn't full, or holding a port that isn't.
        Map<DataCableBlockEntity, List<EnergyHandler>> wants = new HashMap<>();
        for (var entry : loaded.entrySet()) {
            DataCableBlockEntity cable = entry.getValue();
            List<EnergyHandler> targets = new ArrayList<>();
            for (Direction side : Direction.values()) {
                if (cable.port(side) != null) {
                    if (cable.port(side).energy().getAmountAsInt() < cable.port(side).capacity()) targets.add(cable.port(side).energy());
                    continue;
                }
                BlockPos next = entry.getKey().relative(side);
                SimpleEnergyHandler target = machines.contains(next) ? energyAt(next) : null;
                if (target != null && !sources.contains(target) && target.getAmountAsInt() < target.getCapacityAsLong()) targets.add(target);
            }
            if (!targets.isEmpty()) wants.put(cable, targets);
        }
        if (wants.isEmpty()) {
            return;
        }
        // Each cable next to something that wants power pulls it from the nearest cables that hold some, along the
        // shortest open path. Every hop carries at most the slower cable's rate per tick, shared by everything that
        // crosses it, so a slow stretch slows only the power that goes through it. Whoever starts goes round.
        Map<Hop, Integer> used = new HashMap<>();
        List<DataCableBlockEntity> order = new ArrayList<>(wants.keySet());
        java.util.Collections.rotate(order, (int) Math.floorMod(now, (long) order.size()));
        Map<DataCableBlockEntity, Integer> wanted = new HashMap<>();
        for (DataCableBlockEntity cable : order) {
            long room = 0;
            for (EnergyHandler target : wants.get(cable)) room += target.getCapacityAsLong() - target.getAmountAsLong();
            wanted.put(cable, (int) Math.min(Math.min(room, (long) cable.tier().rate() * wants.get(cable).size()), cable.energy().getCapacityAsInt()));
        }
        // When power is short, everyone gets an equal part first; then whoever still has room takes the rest.
        int part = (int) Math.max(1, Math.min(Integer.MAX_VALUE, stored() / order.size()));
        for (DataCableBlockEntity cable : order) {
            pull(cable, Math.min(wanted.get(cable), part) - cable.energy().getAmountAsInt(), loaded, used);
            handOver(cable, wants.get(cable));
        }
        for (DataCableBlockEntity cable : order) {
            pull(cable, wanted.get(cable) - cable.energy().getAmountAsInt(), loaded, used);
            handOver(cable, wants.get(cable));
        }
        shareAdjacentPower();
    }

    /** Hands a cable's power to what it feeds, each an equal part up to the cable's rate. */
    private static void handOver(DataCableBlockEntity cable, List<EnergyHandler> targets) {
        int share = Math.max(1, Math.min(cable.tier().rate(), cable.energy().getAmountAsInt() / targets.size()));
        for (int i = 0; i < targets.size() && cable.energy().getAmountAsInt() > 0; i++) {
            transfer(cable.energy(), targets.get(i), share);
        }
    }

    /** Brings up to {@code amount} into {@code cable} from the nearest cables holding power, hop by hop within each hop's rate. */
    private void pull(DataCableBlockEntity cable, int amount, Map<BlockPos, DataCableBlockEntity> loaded, Map<Hop, Integer> used) {
        if (amount <= 0) {
            return;
        }
        Map<DataCableBlockEntity, DataCableBlockEntity> towards = new HashMap<>();
        java.util.ArrayDeque<DataCableBlockEntity> queue = new java.util.ArrayDeque<>();
        towards.put(cable, cable);
        queue.add(cable);
        while (!queue.isEmpty() && amount > 0) {
            DataCableBlockEntity at = queue.poll();
            if (at != cable && at.energy().getAmountAsInt() > 0) {
                int room = at.energy().getAmountAsInt();
                for (DataCableBlockEntity step = at; step != cable; step = towards.get(step)) {
                    room = Math.min(room, left(step, towards.get(step), used));
                }
                int moved = Math.min(amount, room);
                if (moved > 0) {
                    transfer(at.energy(), cable.energy(), moved);
                    for (DataCableBlockEntity step = at; step != cable; step = towards.get(step)) {
                        used.merge(edge(step, towards.get(step)), moved, Integer::sum);
                    }
                    amount -= moved;
                }
            }
            for (DataCableBlockEntity next : neighbours(at, loaded)) {
                if (!towards.containsKey(next) && left(next, at, used) > 0) {
                    towards.put(next, at);
                    queue.add(next);
                }
            }
        }
    }

    /** What the hop between two touching cables can still carry this tick. */
    private static int left(DataCableBlockEntity a, DataCableBlockEntity b, Map<Hop, Integer> used) {
        return Math.min(a.tier().rate(), b.tier().rate()) - used.getOrDefault(edge(a, b), 0);
    }

    /** The hop between two touching cables, the same in both directions. */
    private record Hop(long low, long high) {}

    private static Hop edge(DataCableBlockEntity a, DataCableBlockEntity b) {
        long x = a.getBlockPos().asLong();
        long y = b.getBlockPos().asLong();
        return new Hop(Math.min(x, y), Math.max(x, y));
    }

    /** The cables this one passes power to: touching, on this network, and not behind a port. */
    private List<DataCableBlockEntity> neighbours(DataCableBlockEntity cable, Map<BlockPos, DataCableBlockEntity> loaded) {
        List<DataCableBlockEntity> found = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            DataCableBlockEntity next = loaded.get(cable.getBlockPos().relative(side));
            if (next != null && cable.port(side) == null && next.port(side.getOpposite()) == null) found.add(next);
        }
        return found;
    }

    private @Nullable DataCableBlockEntity cableAt(BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? cable : null;
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
