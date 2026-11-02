package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.battery.BatteryGroup;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.core.BrainBalance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One crafting network: Data Cables, machines and Archives joined face to face, and blocks joined by a far link. Cables
 * hold no power: what a source pushes into a cable goes straight to the machines on the network that want it, and what
 * they don't take to the Batteries touching its cables. Machines only get power through a cable or a far link (which
 * stands in for one), or from a generator, Power Acceptor or Battery they touch; they never pass it on to each other.
 *
 * <p>A network may stand in more than one dimension. Its blocks are kept by dimension ({@link #parts()}), and each is
 * only ever looked up in its own.
 *
 * <p>A network lives until something on it changes, so what it finds about its blocks the first time (which block
 * entities sit where, which cables touch, which machines are of which kind) is kept and reused every tick.
 */
public final class CableNetwork {
    /** The blocks of the network that stand in one dimension. */
    public record Part(ServerLevel level, Set<BlockPos> cables, Set<BlockPos> machines) {
        public Part {
            cables = Collections.unmodifiableSet(cables);
            machines = Collections.unmodifiableSet(machines);
        }
    }

    /** Two blocks of the network that don't touch but are joined as if a cable ran between them. */
    public record Link(NetworkNode from, NetworkNode to) {}

    /** A loaded block of the network that isn't a cable, with what power is handed to (none for most blocks). */
    private record Member(BlockPos pos, BlockEntity entity, List<? extends EnergyHandler> inlets) {}

    private final List<Part> parts;
    private final Map<ResourceKey<Level>, Part> byDimension = new HashMap<>();
    private final List<Link> links;
    private final boolean complete;
    private @Nullable List<Member> members;
    /** Per part, in the order of {@link #parts}. */
    private @Nullable List<Map<BlockPos, Member>> memberByPos;
    private @Nullable List<Map<BlockPos, DataCableBlockEntity>> loadedCables;
    private @Nullable List<EnergyHandler> consumers;
    private @Nullable List<List<BatteryBlockEntity>> batteriesByPart;
    private @Nullable List<BatteryBlockEntity> batteries;
    private final Map<Class<?>, List<?>> byKind = new HashMap<>();
    private final Map<Function<CableNetwork, ?>, Object> derived = new HashMap<>();
    private long checkedTick = -1;
    private long leaderTick = -1;
    private @Nullable NetworkBrainBlockEntity leader;
    private @Nullable List<Member> brainSpots;
    private int counted = -1;
    private long stateTick = -1;
    private NetworkLimit.@Nullable State state;

    CableNetwork(List<Part> parts, List<Link> links, boolean complete) {
        this.parts = List.copyOf(parts);
        this.links = List.copyOf(links);
        this.complete = complete;
        for (Part part : this.parts) {
            byDimension.put(part.level().dimension(), part);
        }
    }

    /** The network's blocks, one part per dimension it stands in. Never empty. */
    public List<Part> parts() {
        return parts;
    }

    /** The network's blocks in {@code dimension}, or null when it has none there. */
    @Nullable Part part(ResourceKey<Level> dimension) {
        return byDimension.get(dimension);
    }

    /** The far links joining this network, each once. Empty for a network made of touching blocks only. */
    public List<Link> links() {
        return links;
    }

    /**
     * Positions of the network's cables. A bare position can't say which dimension it is in, so a network standing in
     * more than one gives none here; use {@link #parts()} for those.
     */
    public Set<BlockPos> cables() {
        return parts.size() == 1 ? parts.getFirst().cables() : Set.of();
    }

    /** False when the walk stopped at an unloaded chunk, or at a far link whose other end isn't loaded. */
    public boolean complete() {
        return complete;
    }

    /**
     * Positions of the blocks on this network that aren't cables: machines and Archives. Like {@link #cables()}, none
     * for a network standing in more than one dimension.
     */
    public Set<BlockPos> machines() {
        return parts.size() == 1 ? parts.getFirst().machines() : Set.of();
    }

    /** The machines on this network that are loaded, of the given kind, in every dimension. Found once per kind and kept. */
    @SuppressWarnings("unchecked")
    public <T extends MachineBlockEntity> List<T> machines(Class<T> kind) {
        List<T> known = (List<T>) byKind.get(kind);
        if (known == null) {
            List<T> found = new ArrayList<>();
            for (Member member : members()) {
                if (kind.isInstance(member.entity())) found.add(kind.cast(member.entity()));
            }
            for (Map<BlockPos, DataCableBlockEntity> cables : loadedCables()) {
                for (DataCableBlockEntity cable : cables.values()) {
                    for (var port : cable.ports()) if (kind.isInstance(port)) found.add(kind.cast(port));
                }
            }
            known = Collections.unmodifiableList(found);
            byKind.put(kind, known);
        }
        return known;
    }

    /** Something worked out from this network by {@code make} (a constant), found once and kept as long as the network. */
    @SuppressWarnings("unchecked")
    public <T> T derived(Function<CableNetwork, T> make) {
        Object known = derived.get(make);
        if (known == null) {
            known = make.apply(this);
            derived.put(make, known);
        }
        return (T) known;
    }

    /** The loaded blocks of the network that aren't cables, each looked up once in its own dimension. */
    private List<Member> members() {
        if (members == null) {
            List<Member> found = new ArrayList<>();
            List<Map<BlockPos, Member>> byPart = new ArrayList<>(parts.size());
            for (Part part : parts) {
                ServerLevel level = part.level();
                Map<BlockPos, Member> byPos = new HashMap<>();
                for (BlockPos pos : part.machines()) {
                    if (!level.isLoaded(pos)) continue;
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (entity == null) continue;
                    List<? extends EnergyHandler> inlets = entity instanceof MachineBlockEntity machine
                            ? machine.powerInlets()
                            : entity instanceof ArchiveBlockEntity archive ? List.of(archive.energy()) : List.of();
                    Member member = new Member(pos, entity, inlets);
                    found.add(member);
                    byPos.put(pos, member);
                }
                byPart.add(byPos);
            }
            members = found;
            memberByPos = byPart;
        }
        return members;
    }

    private @Nullable Member memberAt(int part, BlockPos pos) {
        members();
        return memberByPos.get(part).get(pos);
    }

    /** The loaded cables of the network, per part, looked up once. */
    private List<Map<BlockPos, DataCableBlockEntity>> loadedCables() {
        if (loadedCables == null) {
            List<Map<BlockPos, DataCableBlockEntity>> byPart = new ArrayList<>(parts.size());
            for (Part part : parts) {
                Map<BlockPos, DataCableBlockEntity> found = new HashMap<>();
                for (BlockPos pos : part.cables()) {
                    DataCableBlockEntity cable = cableAt(part.level(), pos);
                    if (cable != null) found.put(pos, cable);
                }
                byPart.add(found);
            }
            loadedCables = byPart;
        }
        return loadedCables;
    }

    /**
     * Whether a block this network remembers has gone without the network hearing of it. It shouldn't happen, as every
     * change forgets the network, but a network that kept a dead block would hand power to nothing, so it is checked.
     */
    private boolean stale() {
        for (Map<BlockPos, DataCableBlockEntity> cables : loadedCables()) {
            for (DataCableBlockEntity cable : cables.values()) {
                if (cable.isRemoved()) return true;
            }
        }
        for (Member member : members()) {
            if (member.entity().isRemoved()) return true;
        }
        consumers();
        for (BatteryBlockEntity battery : batteries) {
            if (battery.isRemoved()) return true;
        }
        return false;
    }

    /** Every dimension shares one clock. */
    private long now() {
        return parts.getFirst().level().getGameTime();
    }

    /** The brain that runs this network this tick, or null when none is working. */
    public @Nullable NetworkBrainBlockEntity leader() {
        long now = now();
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
            List<Member> spots = new ArrayList<>();
            for (Member member : members()) {
                if (member.entity().getBlockState().getBlock() instanceof NetworkBrainBlock) spots.add(member);
            }
            brainSpots = spots;
        }
        List<NetworkBrainBlockEntity> found = new ArrayList<>(brainSpots.size());
        for (Member spot : brainSpots) {
            if (spot.entity() instanceof NetworkBrainBlockEntity brain) found.add(brain);
        }
        return found;
    }

    /** Machines, Archives and ports on this network. Brains, chambers and cables don't count. */
    public int machineCount() {
        if (counted < 0) {
            int count = 0;
            for (Member member : members()) {
                var entity = member.entity();
                if (entity instanceof MachineBlockEntity machine ? machine.countsTowardLimit() : entity instanceof ArchiveBlockEntity) {
                    count++;
                }
            }
            for (Map<BlockPos, DataCableBlockEntity> cables : loadedCables()) {
                for (DataCableBlockEntity cable : cables.values()) {
                    count += cable.ports().size();
                }
            }
            counted = count;
        }
        return counted;
    }

    /** Whether the network holds more machines than its brain allows, this tick. */
    public NetworkLimit.State limitState() {
        long now = now();
        if (state == null || now != stateTick) {
            stateTick = now;
            NetworkBrainBlockEntity lead = leader();
            int limit = BrainBalance.fromConfig().limit(lead != null, lead == null ? 0 : lead.floors());
            int count = machineCount();
            state = new NetworkLimit.State(count, limit, lead == null ? null : NetworkNode.of(lead.getLevel(), lead.getBlockPos()), count > limit);
        }
        return state;
    }

    /**
     * Whether a cable, machine or Archive at {@code pos} is part of this network. Like {@link #cables()}, false for every
     * position on a network standing in more than one dimension; use {@link #contains(NetworkNode)} there.
     */
    public boolean contains(BlockPos pos) {
        return parts.size() == 1 && inPart(parts.getFirst(), pos);
    }

    /** Whether the block at {@code node}, in its own dimension, is part of this network. */
    public boolean contains(NetworkNode node) {
        Part part = byDimension.get(node.dimension());
        return part != null && inPart(part, node.pos());
    }

    private static boolean inPart(Part part, BlockPos pos) {
        return part.cables().contains(pos) || part.machines().contains(pos);
    }

    /**
     * Cables hold no power. A source that pushes into any cable of the network offers it here. It goes straight to the
     * machines, Archives and ports that still have room, and what they leave to the network's batteries. Only what they
     * can take is accepted, so a source that finds nothing wanting keeps its power (and a generator stops burning), and
     * how much can move in one tick has no limit.
     */
    int feed(int amount, TransactionContext transaction) {
        if (amount <= 0 || !current()) {
            return 0;
        }
        int taken = spread(consumers(), amount, transaction);
        if (taken < amount && !batteries.isEmpty()) {
            taken += spread(batteryGroups(), amount - taken, transaction);
        }
        return taken;
    }

    /** Like {@link #feed}, but only machines, Archives and ports get any: what a battery gives never goes into another. */
    int feedMachines(int amount, TransactionContext transaction) {
        if (amount <= 0 || !current()) {
            return 0;
        }
        return spread(consumers(), amount, transaction);
    }

    /** Checked once a tick: false when a block it remembers has gone, and the network is thrown away. */
    private boolean current() {
        long now = now();
        if (now != checkedTick) {
            checkedTick = now;
            if (stale()) {
                Part first = parts.getFirst();
                Networks.invalidate(first.level(), first.machines().isEmpty() ? first.cables().iterator().next() : first.machines().iterator().next());
                return false;
            }
        }
        return true;
    }

    /** An equal part to each that still has room first, and the rest to whoever can take it. */
    private int spread(List<EnergyHandler> targets, int amount, TransactionContext transaction) {
        return PowerShare.spread(targets, amount, now(), transaction);
    }

    /** Each battery once, grouped with the blocks it touches in its own dimension. */
    private List<EnergyHandler> batteryGroups() {
        if (parts.size() == 1) {
            return BatteryGroup.distinct(parts.getFirst().level(), batteriesByPart.getFirst());
        }
        List<EnergyHandler> found = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            found.addAll(BatteryGroup.distinct(parts.get(i).level(), batteriesByPart.get(i)));
        }
        return found;
    }

    /**
     * The Battery blocks touching the network's cables, found with the machines. A port's face gives a battery nothing.
     * A caller groups them in its own dimension, so a network standing in more than one gives none here.
     */
    public List<BatteryBlockEntity> batteries() {
        consumers();
        return parts.size() == 1 ? batteries : List.of();
    }

    /**
     * Machines and Archives touching a cable, and the ports on cables. Each buffer once. Found once and kept. A port holds
     * no power: it is here as one target per side it may power, each working out its room as it is asked, so the list
     * stays right when the port's machines or upgrade change.
     */
    private List<EnergyHandler> consumers() {
        if (consumers == null) {
            Set<EnergyHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<EnergyHandler> found = new ArrayList<>();
            List<List<BatteryBlockEntity>> foundBatteries = new ArrayList<>(parts.size());
            List<BatteryBlockEntity> allBatteries = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                ServerLevel level = part.level();
                List<BatteryBlockEntity> here = new ArrayList<>();
                for (var entry : loadedCables().get(i).entrySet()) {
                    DataCableBlockEntity cable = entry.getValue();
                    for (Direction side : Direction.values()) {
                        var port = cable.port(side);
                        if (port != null) {
                            for (EnergyHandler inlet : port.powerInlets()) {
                                if (seen.add(inlet)) found.add(inlet);
                            }
                            continue;
                        }
                        // What is in front of a thin acceptor is the acceptor's to trade with, not the cable's to feed.
                        if (cable.acceptor(side) != null) continue;
                        BlockPos next = entry.getKey().relative(side);
                        if (level.isLoaded(next) && level.getBlockEntity(next) instanceof BatteryBlockEntity battery) {
                            here.add(battery);
                            continue;
                        }
                        // Two chambers of one floor both hand over to their brain: count it once.
                        if (part.machines().contains(next)) {
                            Member member = memberAt(i, next);
                            for (EnergyHandler target : member == null ? List.<EnergyHandler>of() : member.inlets()) {
                                if (seen.add(target)) found.add(target);
                            }
                        }
                    }
                }
                foundBatteries.add(Collections.unmodifiableList(here));
                allBatteries.addAll(here);
            }
            // A far link stands in for a cable: the two blocks it joins are fed as if one touched them.
            for (Link link : links) {
                for (NetworkNode end : List.of(link.from(), link.to())) {
                    int i = parts.indexOf(byDimension.get(end.dimension()));
                    Member member = i < 0 ? null : memberAt(i, end.pos());
                    for (EnergyHandler target : member == null ? List.<EnergyHandler>of() : member.inlets()) {
                        if (seen.add(target)) found.add(target);
                    }
                }
            }
            consumers = Collections.unmodifiableList(found);
            batteriesByPart = foundBatteries;
            batteries = Collections.unmodifiableList(allBatteries);
        }
        return consumers;
    }

    private static @Nullable DataCableBlockEntity cableAt(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? cable : null;
    }
}
