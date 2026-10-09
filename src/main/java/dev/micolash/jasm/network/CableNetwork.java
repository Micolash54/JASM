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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One crafting network: Data Cables, machines and Archives joined face to face. Cables hold no power: what a source
 * pushes into a cable goes straight to the machines on the network that want it, and what they don't take to the
 * Batteries touching its cables. Machines only get power through a cable, or from a generator, Power Acceptor or Battery
 * they touch; they never pass it on to each other.
 *
 * <p>A network lives until something on it changes, so what it finds about its blocks the first time (which block
 * entities sit where, which cables touch, which machines are of which kind) is kept and reused every tick.
 */
public final class CableNetwork {
    /** A loaded block of the network that isn't a cable, with its power buffer if it has one. */
    private record Member(BlockPos pos, BlockEntity entity, @Nullable SimpleEnergyHandler energy) {}

    private final ServerLevel level;
    private final Set<BlockPos> cables;
    private final Set<BlockPos> machines;
    private final boolean complete;
    private @Nullable List<Member> members;
    private @Nullable Map<BlockPos, Member> memberByPos;
    private @Nullable Map<BlockPos, DataCableBlockEntity> loadedCables;
    private @Nullable List<EnergyHandler> consumers;
    private @Nullable List<BatteryBlockEntity> batteries;
    private final Map<Class<?>, List<?>> byKind = new HashMap<>();
    private long checkedTick = -1;
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

    /** The machines on this network that are loaded, of the given kind. Found once per kind and kept. */
    @SuppressWarnings("unchecked")
    public <T extends MachineBlockEntity> List<T> machines(Class<T> kind) {
        List<T> known = (List<T>) byKind.get(kind);
        if (known == null) {
            List<T> found = new ArrayList<>();
            for (Member member : members()) {
                if (kind.isInstance(member.entity())) found.add(kind.cast(member.entity()));
            }
            for (DataCableBlockEntity cable : loadedCables().values()) {
                for (var port : cable.ports()) if (kind.isInstance(port)) found.add(kind.cast(port));
            }
            known = Collections.unmodifiableList(found);
            byKind.put(kind, known);
        }
        return known;
    }

    /** The loaded blocks of the network that aren't cables, looked up once. */
    private List<Member> members() {
        if (members == null) {
            List<Member> found = new ArrayList<>(machines.size());
            Map<BlockPos, Member> byPos = new HashMap<>();
            for (BlockPos pos : machines) {
                if (!level.isLoaded(pos)) continue;
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity == null) continue;
                SimpleEnergyHandler energy = entity instanceof MachineBlockEntity machine
                        ? machine.energy()
                        : entity instanceof ArchiveBlockEntity archive ? archive.energy() : null;
                Member member = new Member(pos, entity, energy);
                found.add(member);
                byPos.put(pos, member);
            }
            members = found;
            memberByPos = byPos;
        }
        return members;
    }

    private @Nullable Member memberAt(BlockPos pos) {
        members();
        return memberByPos.get(pos);
    }

    /** The loaded cables of the network, looked up once. */
    private Map<BlockPos, DataCableBlockEntity> loadedCables() {
        if (loadedCables == null) {
            Map<BlockPos, DataCableBlockEntity> found = new HashMap<>();
            for (BlockPos pos : cables) {
                DataCableBlockEntity cable = cableAt(pos);
                if (cable != null) found.put(pos, cable);
            }
            loadedCables = found;
        }
        return loadedCables;
    }

    /**
     * Whether a block this network remembers has gone without the network hearing of it. It shouldn't happen, as every
     * change forgets the network, but a network that kept a dead block would hand power to nothing, so it is checked.
     */
    private boolean stale() {
        for (DataCableBlockEntity cable : loadedCables().values()) {
            if (cable.isRemoved()) return true;
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
            for (Member member : members()) {
                if (member.entity().getBlockState().getBlock() instanceof NetworkBrainBlock) spots.add(member.pos());
            }
            brainSpots = spots;
        }
        List<NetworkBrainBlockEntity> found = new ArrayList<>(brainSpots.size());
        for (BlockPos pos : brainSpots) {
            Member member = memberAt(pos);
            if (member != null && member.entity() instanceof NetworkBrainBlockEntity brain) found.add(brain);
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
            for (DataCableBlockEntity cable : loadedCables().values()) {
                count += cable.ports().size();
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
            int limit = BrainBalance.fromConfig().limit(lead != null, lead == null ? 0 : lead.floors());
            int count = machineCount();
            state = new NetworkLimit.State(count, limit, lead == null ? null : lead.getBlockPos(), count > limit);
        }
        return state;
    }

    public boolean contains(BlockPos pos) {
        return cables.contains(pos) || machines.contains(pos);
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
            taken += spread(BatteryGroup.distinct(level, batteries), amount - taken, transaction);
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
        long now = level.getGameTime();
        if (now != checkedTick) {
            checkedTick = now;
            if (stale()) {
                Networks.invalidate(level, machines.isEmpty() ? cables.iterator().next() : machines.iterator().next());
                return false;
            }
        }
        return true;
    }

    /** An equal part to each that still has room first, and the rest to whoever can take it. */
    private int spread(List<EnergyHandler> targets, int amount, TransactionContext transaction) {
        int wanting = 0;
        for (EnergyHandler target : targets) {
            if (target.getAmountAsLong() < target.getCapacityAsLong()) wanting++;
        }
        if (wanting == 0) {
            return 0;
        }
        // Whoever starts goes round, so the odd FE left over doesn't always land on the same block.
        int start = (int) Math.floorMod(level.getGameTime(), (long) targets.size());
        int share = Math.max(1, amount / wanting);
        int taken = 0;
        for (int i = 0; i < targets.size() && taken < amount; i++) {
            taken += targets.get((start + i) % targets.size()).insert(Math.min(share, amount - taken), transaction);
        }
        for (int i = 0; i < targets.size() && taken < amount; i++) {
            taken += targets.get((start + i) % targets.size()).insert(amount - taken, transaction);
        }
        return taken;
    }

    /** The Battery blocks touching the network's cables, found with the machines. A port's face gives a battery nothing. */
    public List<BatteryBlockEntity> batteries() {
        consumers();
        return batteries;
    }

    /**
     * Machines and Archives touching a cable, and the ports on cables. Each buffer once. Found once and kept. A block in
     * front of a port gets nothing from here: only a port with a Power Upgrade passes power on to it.
     */
    private List<EnergyHandler> consumers() {
        if (consumers == null) {
            Set<EnergyHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<EnergyHandler> found = new ArrayList<>();
            List<BatteryBlockEntity> foundBatteries = new ArrayList<>();
            for (var entry : loadedCables().entrySet()) {
                DataCableBlockEntity cable = entry.getValue();
                for (Direction side : Direction.values()) {
                    var port = cable.port(side);
                    if (port != null) {
                        if (seen.add(port.energy())) found.add(port.energy());
                        continue;
                    }
                    BlockPos next = entry.getKey().relative(side);
                    if (level.isLoaded(next) && level.getBlockEntity(next) instanceof BatteryBlockEntity battery) {
                        foundBatteries.add(battery);
                        continue;
                    }
                    // Two chambers of one floor both hand over to their brain: count it once.
                    SimpleEnergyHandler target = machines.contains(next) ? energyAt(next) : null;
                    if (target != null && seen.add(target)) found.add(target);
                }
            }
            consumers = Collections.unmodifiableList(found);
            batteries = Collections.unmodifiableList(foundBatteries);
        }
        return consumers;
    }

    private @Nullable DataCableBlockEntity cableAt(BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? cable : null;
    }

    private @Nullable SimpleEnergyHandler energyAt(BlockPos pos) {
        Member member = memberAt(pos);
        return member == null ? null : member.energy();
    }
}
