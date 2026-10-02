package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.BrainLevels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
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
    private long leaderTick = -1;
    private @Nullable NetworkBrainBlockEntity leader;
    private @Nullable List<BlockPos> brainSpots;
    private int counted = -1;
    private long stateTick = -1;
    private NetworkLimit.@Nullable State state;

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

    /** The brain that runs this network this tick, or null when none is working. */
    public @Nullable NetworkBrainBlockEntity leader() {
        long now = level.getGameTime();
        if (now != leaderTick) {
            leaderTick = now;
            leader = NetworkLimit.leader(brains());
        }
        return leader;
    }

    /**
     * The loaded brains on this network. Where they sit is found once per network, as any change to the network makes a
     * new one; brains never sit on a cable as ports.
     */
    private List<NetworkBrainBlockEntity> brains() {
        if (brainSpots == null) {
            List<BlockPos> spots = new ArrayList<>();
            for (BlockPos pos : machines) {
                if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof NetworkBrainBlock) spots.add(pos);
            }
            brainSpots = spots;
        }
        List<NetworkBrainBlockEntity> found = new ArrayList<>(brainSpots.size());
        for (BlockPos pos : brainSpots) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkBrainBlockEntity brain) found.add(brain);
        }
        return found;
    }

    /** Machines, Archives and ports on this network. Brains, chambers and cables don't count. */
    public int machineCount() {
        if (counted < 0) {
            int count = 0;
            for (BlockPos pos : machines) {
                if (!level.isLoaded(pos)) continue;
                var entity = level.getBlockEntity(pos);
                if (entity instanceof MachineBlockEntity machine ? machine.countsTowardLimit() : entity instanceof ArchiveBlockEntity) {
                    count++;
                }
            }
            for (BlockPos pos : cables) {
                DataCableBlockEntity cable = cableAt(pos);
                if (cable != null) count += cable.ports().size();
            }
            counted = count;
        }
        return counted;
    }

    /** Whether the network holds more machines than its brain allows, this tick. */
    public NetworkLimit.State limitState() {
        long now = level.getGameTime();
        if (state == null || now != stateTick) {
            stateTick = now;
            NetworkBrainBlockEntity lead = leader();
            int limit = BrainLevels.machineLimit(lead == null ? 0 : lead.shownLevel(), BrainBalance.fromConfig());
            int count = machineCount();
            state = new NetworkLimit.State(count, limit, lead == null ? null : lead.getBlockPos(), count > limit);
        }
        return state;
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
     * Moves power along, once per game tick however often it is asked. Each cable next to a machine or port that wants
     * power pulls it from the nearest cables that hold some, and from nearly full machines with spare charge when another
     * machine needs it more, and hands it over, so power crosses a long line in one tick.
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
        // A nearly full machine, supplied directly, can feed the network too. Only power that came from outside the
        // cables can go on (see NetworkEnergy), so power a cable delivered isn't handed straight back, and its own
        // running cost stays in its buffer. Its spare is only taken where power is wanted, through the cables it
        // touches, so none is pushed into a cable that leads nowhere. A full block whose power all came from the cables
        // passes on power from a generator or battery beside it instead, straight from that source, so a block that
        // uses nothing while idle can't shut a generator out.
        Set<SimpleEnergyHandler> sources = new HashSet<>();
        Map<SimpleEnergyHandler, Spare> spares = new IdentityHashMap<>();
        Map<DataCableBlockEntity, List<Spare>> spareBeside = new HashMap<>();
        for (BlockPos pos : machines) {
            SimpleEnergyHandler source = energyAt(pos);
            if (source == null) {
                continue;
            }
            if (leastCharged == null || leastCharged == source
                    || source.getAmountAsLong() * 10 < source.getCapacityAsLong() * 9
                    || (long) leastCharged.getAmountAsInt() * source.getCapacityAsLong()
                        >= (long) source.getAmountAsInt() * leastCharged.getCapacityAsLong()) {
                continue;
            }
            int spareNow = Math.max(0, Math.min(source instanceof NetworkEnergy energy ? energy.own() : 0, source.getAmountAsInt() - reserveAt(pos)));
            List<EnergyHandler> outside = spareNow > 0 ? List.of() : outsideSources(pos);
            if (spareNow <= 0 && outside.isEmpty()) {
                continue;
            }
            for (Direction side : Direction.values()) {
                DataCableBlockEntity cable = loaded.get(pos.relative(side));
                if (cable != null && cable.port(side.getOpposite()) == null) {
                    sources.add(source);
                    Spare spare = spares.computeIfAbsent(source, s -> new Spare(s, spareNow));
                    for (EnergyHandler handler : outside) if (!spare.outside.contains(handler)) spare.outside.add(handler);
                    List<Spare> beside = spareBeside.computeIfAbsent(cable, c -> new ArrayList<>());
                    if (!beside.contains(spare)) {
                        beside.add(spare);
                        spare.intake += cable.tier().rate();
                    }
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
                // Two chambers of one cube both hand over to their brain: count it once.
                if (target != null && !sources.contains(target) && target.getAmountAsInt() < target.getCapacityAsLong()
                        && !targets.contains(target)) targets.add(target);
            }
            if (!targets.isEmpty()) wants.put(cable, targets);
        }
        if (wants.isEmpty()) {
            return;
        }
        // Each cable next to something that wants power pulls it from the nearest cables that hold some or touch a
        // machine with spare, along the shortest open path. Every hop carries at most the slower cable's rate per tick,
        // shared by everything that crosses it, so a slow stretch slows only the power that goes through it. Whoever
        // starts goes round.
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
        long spareTotal = 0;
        // An outside source counts once, and for no more than the cables beside its block can take in.
        Set<EnergyHandler> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Spare spare : spares.values()) {
            spareTotal += spare.left;
            long outside = 0;
            for (EnergyHandler handler : spare.outside) if (counted.add(handler)) outside += handler.getAmountAsLong();
            spareTotal += Math.min(outside, spare.intake);
        }
        long available = stored() + spareTotal;
        if (available == 0) {
            shareAdjacentPower();
            return;
        }
        int part = (int) Math.max(1, Math.min(Integer.MAX_VALUE, available / order.size()));
        Map<DataCableBlockEntity, Integer> drawn = new HashMap<>();
        for (DataCableBlockEntity cable : order) {
            pull(cable, Math.min(wanted.get(cable), part) - cable.energy().getAmountAsInt(), loaded, used, spareBeside, drawn);
            handOver(cable, wants.get(cable));
        }
        for (DataCableBlockEntity cable : order) {
            pull(cable, wanted.get(cable) - cable.energy().getAmountAsInt(), loaded, used, spareBeside, drawn);
            handOver(cable, wants.get(cable));
        }
        shareAdjacentPower();
    }

    /** Hands a cable's power to what it feeds, each an equal part up to the cable's rate. */
    private static void handOver(DataCableBlockEntity cable, List<EnergyHandler> targets) {
        int share = Math.max(1, Math.min(cable.tier().rate(), cable.energy().getAmountAsInt() / targets.size()));
        for (int i = 0; i < targets.size() && cable.energy().getAmountAsInt() > 0; i++) {
            EnergyHandler target = targets.get(i);
            int moved = transfer(cable.energy(), target, share);
            if (moved > 0 && target instanceof NetworkEnergy energy) {
                energy.fromNetwork(moved);
            }
        }
    }

    /**
     * Brings up to {@code amount} into {@code cable} from the nearest cables holding power or touching a machine with
     * spare, hop by hop within each hop's rate. A machine gives into a cable at most that cable's rate each tick.
     */
    private void pull(DataCableBlockEntity cable, int amount, Map<BlockPos, DataCableBlockEntity> loaded, Map<Hop, Integer> used,
            Map<DataCableBlockEntity, List<Spare>> spareBeside, Map<DataCableBlockEntity, Integer> drawn) {
        if (amount <= 0) {
            return;
        }
        Map<DataCableBlockEntity, DataCableBlockEntity> towards = new HashMap<>();
        java.util.ArrayDeque<DataCableBlockEntity> queue = new java.util.ArrayDeque<>();
        towards.put(cable, cable);
        queue.add(cable);
        while (!queue.isEmpty() && amount > 0) {
            DataCableBlockEntity at = queue.poll();
            List<Spare> beside = spareBeside.getOrDefault(at, List.of());
            boolean holds = at != cable && at.energy().getAmountAsInt() > 0;
            // Only cables with power to give, in them or beside them, walk back along their path.
            int path = Integer.MAX_VALUE;
            if (holds || !beside.isEmpty()) {
                for (DataCableBlockEntity step = at; step != cable; step = towards.get(step)) {
                    path = Math.min(path, left(step, towards.get(step), used));
                }
            }
            if (holds) {
                int moved = transfer(at.energy(), cable.energy(), Math.min(amount, Math.min(path, at.energy().getAmountAsInt())));
                use(at, cable, towards, used, moved);
                amount -= moved;
                path -= moved;
            }
            for (Spare spare : beside) {
                int intake = at.tier().rate() - drawn.getOrDefault(at, 0);
                int moved = transfer(spare.handler, cable.energy(), Math.min(Math.min(amount, path), Math.min(intake, spare.left)));
                if (moved > 0 && spare.handler instanceof NetworkEnergy energy) {
                    energy.gave(moved);
                }
                spare.left -= moved;
                drawn.merge(at, moved, Integer::sum);
                use(at, cable, towards, used, moved);
                amount -= moved;
                path -= moved;
                for (EnergyHandler handler : spare.outside) {
                    int room = cable.energy().getCapacityAsInt() - cable.energy().getAmountAsInt();
                    int fromOutside = take(handler, cable.energy(),
                            Math.min(Math.min(Math.min(amount, path), room), at.tier().rate() - drawn.getOrDefault(at, 0)));
                    drawn.merge(at, fromOutside, Integer::sum);
                    use(at, cable, towards, used, fromOutside);
                    amount -= fromOutside;
                    path -= fromOutside;
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

    /** Notes {@code moved} FE crossing every hop from {@code from} back to {@code to}. */
    private static void use(DataCableBlockEntity from, DataCableBlockEntity to, Map<DataCableBlockEntity, DataCableBlockEntity> towards,
            Map<Hop, Integer> used, int moved) {
        if (moved <= 0) {
            return;
        }
        for (DataCableBlockEntity step = from; step != to; step = towards.get(step)) {
            used.merge(edge(step, towards.get(step)), moved, Integer::sum);
        }
    }

    /** A nearly full machine's spare charge this tick, and how much of it is left to take. */
    private static final class Spare {
        final SimpleEnergyHandler handler;
        int left;
        /** What the cables beside it can take in each tick, together. */
        int intake;
        /** Generators, batteries and other mods' blocks beside it that it passes power on from. */
        final List<EnergyHandler> outside = new ArrayList<>(2);

        Spare(SimpleEnergyHandler handler, int left) {
            this.handler = handler;
            this.left = left;
        }
    }

    /**
     * Power sources beside a network block that belong to no network: generators, batteries and other mods' blocks that
     * give power on that side and take none in. Machines that also take power in are left alone, so their power stays
     * theirs.
     */
    private List<EnergyHandler> outsideSources(BlockPos pos) {
        List<EnergyHandler> found = new ArrayList<>(2);
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (machines.contains(next) || cables.contains(next) || !level.isLoaded(next)) continue;
            var entity = level.getBlockEntity(next);
            if (entity == null || entity instanceof DataCableBlockEntity || entity instanceof MachineBlockEntity
                    || entity instanceof ArchiveBlockEntity) continue;
            EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, next, side.getOpposite());
            if (handler != null && handler.getAmountAsLong() > 0 && onlyGives(handler)) found.add(handler);
        }
        return found;
    }

    /**
     * Whether a handler gives power and takes none in. Tried in a transaction that is never kept; one FE is taken out
     * first, so a full two-way store still shows that it takes power in.
     */
    private static boolean onlyGives(EnergyHandler handler) {
        try (Transaction tx = Transaction.openRoot()) {
            return handler.extract(1, tx) > 0 && handler.insert(1, tx) == 0;
        }
    }

    /**
     * Moves up to {@code limit} FE out of a source outside the network, taking out first and putting in what came out,
     * so a source that gives less per call than asked still gives what it can. Returns how much moved.
     */
    private static int take(EnergyHandler source, EnergyHandler target, int limit) {
        if (limit <= 0) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int got = source.extract(limit, tx);
            if (got > 0 && target.insert(got, tx) == got) {
                tx.commit();
                return got;
            }
        }
        return 0;
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

    /** What a block keeps back for its own next tick. A formed chamber holds its brain's power, so it keeps the brain's. */
    private int reserveAt(BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber) {
            NetworkBrainBlockEntity brain = chamber.brain();
            return brain == null ? 0 : brain.drainPerTick();
        }
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
                // A formed chamber holds its brain's power: nothing to share with itself.
                if (b == null || b == a) {
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
                    share(a, b, Math.min(difference, Math.max(0, a.getAmountAsInt() - reserveAt(pos))));
                } else if (difference < 0) {
                    share(b, a, Math.min(-difference, Math.max(0, b.getAmountAsInt() - reserveAt(next))));
                }
            }
        }
    }

    /**
     * Moves power between two touching blocks. Power that came from outside the cables moves first and stays shareable;
     * the rest stays power the network gave.
     */
    private static void share(SimpleEnergyHandler from, SimpleEnergyHandler to, int limit) {
        int own = from instanceof NetworkEnergy energy ? energy.own() : 0;
        int moved = transfer(from, to, limit);
        int ownMoved = Math.min(moved, own);
        if (from instanceof NetworkEnergy energy) {
            energy.gave(ownMoved);
        }
        if (to instanceof NetworkEnergy energy) {
            energy.fromNetwork(moved - ownMoved);
        }
    }

    /** Moves up to {@code limit} FE and returns how much moved. */
    private static int transfer(EnergyHandler source, EnergyHandler target, int limit) {
        if (limit <= 0) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(Math.min(limit, source.getAmountAsInt()), tx);
            if (accepted > 0 && source.extract(accepted, tx) == accepted) {
                tx.commit();
                return accepted;
            }
        }
        return 0;
    }
}
