package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Finds crafting networks and keeps them until something changes. A network is worked out the first time anything
 * asks about one of its blocks, by walking from block to block, and across far links to blocks that don't touch,
 * possibly in another dimension. Placing or removing a cable, a machine or an Archive throws away the network it
 * touches, and only that one; a chunk loading or unloading throws away the networks that have a block in it, or that
 * stopped at its edge or at a far link's end inside it. Each is worked out again when next asked, keeping the power its
 * cables held.
 *
 * <p>Each dimension keeps its own index, so equal positions in two dimensions never meet. A network that stands in
 * several dimensions is listed in each, and thrown away from all of them at once.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Networks {
    /** Largest network walked; blocks beyond it are left out. */
    public static final int MAX_BLOCKS = 4_096;

    private static final Map<ServerLevel, Networks> LEVELS = new WeakHashMap<>();
    /** Far links, both ways round. Kept while the server runs; whoever links two blocks unlinks them. */
    private static final Map<NetworkNode, Set<NetworkNode>> LINKS = new HashMap<>();

    private final ServerLevel level;
    private final Map<BlockPos, CableNetwork> byPos = new HashMap<>();
    private final Set<CableNetwork> all = new LinkedHashSet<>();
    /** The networks with a block in each chunk, or that stopped at its edge because it wasn't loaded. */
    private final Map<Long, Set<CableNetwork>> byChunk = new HashMap<>();
    private final Map<CableNetwork, Set<Long>> chunksOf = new HashMap<>();

    private Networks(ServerLevel level) {
        this.level = level;
    }

    private static Networks of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, Networks::new);
    }

    /** The network {@code pos} belongs to, or null if nothing there is part of one. */
    public static @Nullable CableNetwork at(ServerLevel level, BlockPos pos) {
        return of(level).find(pos);
    }

    /**
     * Joins two network blocks that don't touch as if a cable ran between them, in one dimension or across two. The
     * link carries only while both ends are loaded network blocks whose owners could share a cable, and a link never
     * loads a chunk. It isn't saved: it lasts until {@link #unlink} or the server stops. The networks at both ends are
     * worked out again when next asked. Server thread only.
     */
    public static void link(MinecraftServer server, NetworkNode a, NetworkNode b) {
        if (a.equals(b)) {
            return;
        }
        boolean added = LINKS.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        added |= LINKS.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        if (added) {
            forgetAt(server, a);
            forgetAt(server, b);
        }
    }

    /** Takes back a {@link #link}. The networks at both ends are worked out again when next asked. */
    public static void unlink(MinecraftServer server, NetworkNode a, NetworkNode b) {
        boolean removed = removeHalf(a, b);
        removed |= removeHalf(b, a);
        if (removed) {
            forgetAt(server, a);
            forgetAt(server, b);
        }
    }

    private static boolean removeHalf(NetworkNode from, NetworkNode to) {
        Set<NetworkNode> far = LINKS.get(from);
        if (far == null || !far.remove(to)) {
            return false;
        }
        if (far.isEmpty()) {
            LINKS.remove(from);
        }
        return true;
    }

    /** Forgets the network holding {@code node}, if one is known; it doesn't load anything to find out. */
    private static void forgetAt(MinecraftServer server, NetworkNode node) {
        ServerLevel level = server.getLevel(node.dimension());
        Networks networks = level == null ? null : LEVELS.get(level);
        CableNetwork network = networks == null ? null : networks.byPos.get(node.pos());
        if (network != null) {
            drop(network);
        }
    }

    public static boolean canConnect(ServerLevel level, BlockPos from, BlockPos to) {
        Networks networks = of(level);
        return networks.isMember(from) && networks.isMember(to)
                && networks.joins(level.getBlockState(from), level.getBlockState(to), from, to) && networks.ownerCompatible(from, to);
    }

    private void refreshCableShapes(BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof DataCableBlock cable) {
            cable.refreshConnections(level, pos);
        }
    }

    public static void ownerChanged(ServerLevel level, BlockPos pos) {
        invalidate(level, pos);
        Networks networks = of(level);
        for (Direction side : Direction.values()) {
            networks.refreshCableShapes(pos.relative(side));
        }
    }

    /** Something changed at {@code pos}: the networks there and beside it are worked out again when next needed. */
    public static void invalidate(ServerLevel level, BlockPos pos) {
        Networks networks = LEVELS.get(level);
        if (networks != null) {
            networks.dropAround(pos);
        }
    }

    /**
     * Forgets the networks at {@code pos} and beside it. Their blocks, and any blocked cable touching them, are checked
     * again for a change of owner, and their cables' arms are redrawn.
     */
    private void dropAround(BlockPos pos) {
        if (!LINKS.isEmpty()) {
            // A far link's other end may be on a network that couldn't count this spot until now.
            for (NetworkNode far : LINKS.getOrDefault(NetworkNode.of(level, pos), Set.of())) {
                forgetAt(level.getServer(), far);
            }
        }
        Set<BlockPos> previous = new HashSet<>();
        previous.add(pos.immutable());
        for (Direction side : Direction.values()) {
            previous.add(pos.relative(side));
        }
        for (BlockPos at : List.copyOf(previous)) {
            CableNetwork network = byPos.get(at);
            if (network != null) {
                CableNetwork.Part here = network.part(level.dimension());
                if (here != null) {
                    previous.addAll(here.cables());
                    previous.addAll(here.machines());
                }
                drop(network);
            }
        }
        CableClaims claims = CableClaims.get(level);
        Set<BlockPos> blocked = new HashSet<>();
        for (BlockPos at : previous) {
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (!previous.contains(next) && level.isLoaded(next) && claims.blocked(level, next)) {
                    blocked.add(next);
                }
            }
        }
        previous.forEach(this::refreshBlocked);
        blocked.forEach(this::refreshBlocked);
        previous.forEach(this::refreshCableShapes);
        previous.forEach(this::syncPortOwners);
        blocked.forEach(this::syncPortOwners);
    }

    private void syncPortOwners(BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable && !cable.ports().isEmpty()) {
            cable.syncOwners();
        }
    }

    /** Forgets {@code network} in every dimension it is listed in. A server has only a few dimensions. */
    private static void drop(CableNetwork network) {
        for (Networks networks : List.copyOf(LEVELS.values())) {
            networks.dropHere(network);
        }
    }

    private void dropHere(CableNetwork network) {
        if (!all.remove(network)) {
            return;
        }
        CableNetwork.Part here = network.part(level.dimension());
        if (here != null) {
            for (BlockPos pos : here.cables()) {
                byPos.remove(pos, network);
            }
            for (BlockPos pos : here.machines()) {
                byPos.remove(pos, network);
            }
        }
        Set<Long> chunks = chunksOf.remove(network);
        if (chunks != null) {
            for (long key : chunks) {
                Set<CableNetwork> there = byChunk.get(key);
                if (there != null && there.remove(network) && there.isEmpty()) {
                    byChunk.remove(key);
                }
            }
        }
    }

    /** A chunk came or went: the networks with a block in it, or waiting at its edge, are forgotten. */
    private void chunkChanged(long key) {
        Set<CableNetwork> there = byChunk.get(key);
        if (there == null) {
            return;
        }
        for (CableNetwork network : List.copyOf(there)) {
            drop(network);
        }
    }

    /** Give a new cable the owner of the network it extends. A bridge between two owners stays separate. */
    public static void placedCable(ServerLevel level, BlockPos pos) {
        Networks networks = of(level);
        networks.initializeNeighbourCables(pos);
        Set<UUID> owners = networks.neighbourOwners(pos);
        if (owners.size() > 1) {
            Set<UUID> established = new HashSet<>();
            Set<BlockPos> lone = new HashSet<>();
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                UUID owner = networks.ownerAt(next);
                if (owner == null) {
                    continue;
                }
                if (networks.hasOtherNeighbour(next, pos)) {
                    established.add(owner);
                } else if (networks.isBlock(next)) {
                    lone.add(next);
                }
            }
            if (!lone.isEmpty() && established.size() == 1) {
                UUID owner = established.iterator().next();
                String name = networks.nameFor(owner, pos);
                for (BlockPos next : lone) {
                    networks.adopt(next, owner, name);
                }
                owners = networks.neighbourOwners(pos);
            }
        }
        UUID owner = owners.size() == 1 ? owners.iterator().next() : null;
        CableClaims.get(level).set(level, pos, owner, owners.size() > 1);
        if (owner != null) {
            networks.claimUnowned(pos, owner);
        }
    }

    /** A newly placed machine adopts a single neighboring network; a two-owner bridge stays on its own. */
    public static void placedMachine(ServerLevel level, BlockPos pos) {
        Networks networks = of(level);
        networks.initializeNeighbourCables(pos);
        Set<UUID> owners = networks.neighbourOwners(pos);
        networks.setBlocked(pos, owners.size() > 1);
        if (owners.size() == 1 && networks.isBlock(pos)) {
            UUID owner = owners.iterator().next();
            networks.adopt(pos, owner, networks.nameFor(owner, pos));
            networks.claimUnowned(pos, owner);
        } else if (owners.isEmpty() && networks.ownerAt(pos) != null) {
            networks.claimUnowned(pos, networks.ownerAt(pos));
        }
        invalidate(level, pos);
        for (Direction side : Direction.values()) {
            networks.refreshCableShapes(pos.relative(side));
        }
    }

    private Set<UUID> neighbourOwners(BlockPos pos) {
        Set<UUID> owners = new HashSet<>();
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!isMember(next) || !joins(level.getBlockState(pos), level.getBlockState(next), pos, next)
                    || contestedMachine(next) || CableClaims.get(level).blocked(level, next)) {
                continue;
            }
            UUID owner = ownerAt(next);
            if (owner != null) {
                owners.add(owner);
            }
        }
        return owners;
    }

    private @Nullable UUID ownerAt(BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        if (level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            return CableClaims.get(level).owner(level, pos);
        }
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            if (machine.owner() != null) {
                CableClaims.get(level).rememberOwner(machine.owner(), machine.ownerName());
            }
            return machine.owner();
        }
        if (level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive) {
            if (archive.ownerId() != null) {
                CableClaims.get(level).rememberOwner(archive.ownerId(), archive.ownerName());
            }
            return archive.ownerId();
        }
        return null;
    }

    private boolean hasOtherNeighbour(BlockPos pos, BlockPos except) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!next.equals(except) && isMember(next) && joins(level.getBlockState(pos), level.getBlockState(next), pos, next)
                    && ownerCompatible(pos, next)) {
                return true;
            }
        }
        return false;
    }

    private String nameFor(UUID owner, BlockPos pos) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        seen.add(pos);
        for (Direction side : Direction.values()) {
            queue.add(pos.relative(side));
        }
        while (!queue.isEmpty() && seen.size() < MAX_BLOCKS) {
            BlockPos next = queue.poll();
            if (!seen.add(next) || !isMember(next) || !owner.equals(ownerAt(next))) {
                continue;
            }
            if (level.getBlockEntity(next) instanceof MachineBlockEntity machine) {
                return machine.ownerName();
            }
            if (level.getBlockEntity(next) instanceof ArchiveBlockEntity archive) {
                return archive.ownerName();
            }
            for (Direction side : Direction.values()) {
                BlockPos more = next.relative(side);
                if (!seen.contains(more) && isMember(more) && joins(level.getBlockState(next), level.getBlockState(more), next, more)) {
                    queue.add(more);
                }
            }
        }
        return CableClaims.get(level).ownerName(owner);
    }

    private void adopt(BlockPos pos, UUID owner, String name) {
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            machine.adoptOwner(owner, name);
        } else if (level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive) {
            archive.adoptOwner(owner, name);
        }
    }

    private void setBlocked(BlockPos pos, boolean blocked) {
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            machine.setNetworkBlocked(blocked);
        } else if (level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive) {
            archive.setNetworkBlocked(blocked);
        }
    }

    private void claimUnowned(BlockPos start, UUID owner) {
        CableClaims claims = CableClaims.get(level);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            if (!seen.add(pos) || !level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockState(pos).getBlock() instanceof DataCableBlock
                    && !claims.blocked(level, pos) && claims.owner(level, pos) == null) {
                claims.set(level, pos, owner, false);
            }
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (!seen.contains(next) && level.isLoaded(next)
                        && level.getBlockState(next).getBlock() instanceof DataCableBlock
                        && claims.owner(level, next) == null && !claims.blocked(level, next)
                        && joins(level.getBlockState(pos), level.getBlockState(next), pos, next)) {
                    queue.add(next);
                }
            }
        }
    }

    private @Nullable CableNetwork find(BlockPos pos) {
        CableNetwork known = byPos.get(pos);
        if (known != null) {
            return known;
        }
        if (!isMember(pos)) {
            return null;
        }
        prepare(pos);
        known = byPos.get(pos);
        if (known != null) {
            return known;
        }
        CableNetwork network = walk(pos.immutable());
        EncodingTerminalBlockEntity.networkSeen(level, network);
        AutocraftState.get(level.getServer()).mergePairings(network.machines(EncodingTerminalBlockEntity.class).stream()
                .map(EncodingTerminalBlockEntity::ensureId).toList());
        return network;
    }

    /** Old worlds' cables get their owners, and a contested block a second look, before a walk starts or enters here. */
    private void prepare(BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            initializeCables(pos, null);
        } else {
            for (Direction side : Direction.values()) {
                initializeCables(pos.relative(side), null);
            }
        }
        if (refreshBlocked(pos)) {
            dropAround(pos);
        }
    }

    /** A block the walk reached: the index of its dimension, and where it stands. */
    private record Step(Networks at, BlockPos pos) {}

    /** What the walk found in one dimension. */
    private static final class Found {
        final Set<BlockPos> cables = new HashSet<>();
        final Set<BlockPos> machines = new HashSet<>();
        final Set<BlockPos> seen = new HashSet<>();
        final Set<Long> chunks = new HashSet<>();
    }

    /**
     * Walks out from {@code start} face to face, and across far links, and lists the network in every dimension it
     * reached. Each block is a dimension and a position, so a block is never counted twice, however many ways lead to it.
     */
    private CableNetwork walk(BlockPos start) {
        Map<Networks, Found> found = new LinkedHashMap<>();
        Set<CableNetwork.Link> links = new LinkedHashSet<>();
        ArrayDeque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(this, start));
        found.computeIfAbsent(this, k -> new Found()).seen.add(start);
        int seen = 1;
        boolean complete = true;
        while (!queue.isEmpty() && seen <= MAX_BLOCKS) {
            Step step = queue.poll();
            Networks here = step.at();
            ServerLevel world = here.level;
            BlockPos at = step.pos();
            Found there = found.get(here);
            BlockState state = world.getBlockState(at);
            boolean cable = state.getBlock() instanceof DataCableBlock;
            (cable ? there.cables : there.machines).add(at);
            there.chunks.add(ChunkPos.pack(at));
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (!world.isLoaded(next)) {
                    // The walk stops here; when that chunk comes in, this network is worked out again.
                    complete = false;
                    there.chunks.add(ChunkPos.pack(next));
                }
                if (!there.seen.contains(next) && world.isLoaded(next) && here.joins(state, world.getBlockState(next), at, next)
                        && here.ownerCompatible(at, next)) {
                    there.seen.add(next);
                    queue.add(new Step(here, next));
                    seen++;
                }
            }
            Set<NetworkNode> far = LINKS.isEmpty() ? null : LINKS.get(NetworkNode.of(world, at));
            if (far == null) {
                continue;
            }
            NetworkNode from = NetworkNode.of(world, at);
            for (NetworkNode to : far) {
                ServerLevel otherWorld = world.getServer().getLevel(to.dimension());
                if (otherWorld == null) {
                    // A dimension that is gone: the link waits, and nothing stands in for it.
                    complete = false;
                    continue;
                }
                Networks other = of(otherWorld);
                Found reached = found.computeIfAbsent(other, k -> new Found());
                if (!otherWorld.isLoaded(to.pos())) {
                    complete = false;
                    reached.chunks.add(ChunkPos.pack(to.pos()));
                    continue;
                }
                if (!other.isMember(to.pos())) {
                    continue;
                }
                if (!reached.seen.contains(to.pos())) {
                    other.prepare(to.pos());
                    CableNetwork old = other.byPos.get(to.pos());
                    if (old != null) {
                        drop(old);
                    }
                }
                if (!compatible(here, at, other, to.pos())) {
                    continue;
                }
                links.add(ordered(from, to));
                if (reached.seen.add(to.pos())) {
                    queue.add(new Step(other, to.pos()));
                    seen++;
                }
            }
        }
        List<CableNetwork.Part> parts = new ArrayList<>();
        found.forEach((networks, area) -> {
            if (!area.cables.isEmpty() || !area.machines.isEmpty()) {
                parts.add(new CableNetwork.Part(networks.level, area.cables, area.machines));
            }
        });
        CableNetwork network = new CableNetwork(parts, List.copyOf(links), complete && queue.isEmpty());
        found.forEach((networks, area) -> networks.add(network, area));
        return network;
    }

    /** Lists a network just walked in this dimension: its blocks here, and the chunks here it depends on. */
    private void add(CableNetwork network, Found area) {
        all.add(network);
        area.cables.forEach(p -> byPos.put(p, network));
        area.machines.forEach(p -> byPos.put(p, network));
        chunksOf.put(network, area.chunks);
        for (long key : area.chunks) {
            byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(network);
        }
    }

    private static final Comparator<NetworkNode> NODE_ORDER = Comparator
            .comparing((NetworkNode n) -> n.dimension().identifier().toString())
            .thenComparingLong(n -> n.pos().asLong());

    /** A link written the same way whichever end it was reached from. */
    private static CableNetwork.Link ordered(NetworkNode a, NetworkNode b) {
        return NODE_ORDER.compare(a, b) <= 0 ? new CableNetwork.Link(a, b) : new CableNetwork.Link(b, a);
    }

    private boolean isMember(BlockPos pos) {
        return level.isLoaded(pos) && (level.getBlockState(pos).getBlock() instanceof DataCableBlock || isBlock(pos));
    }

    /** A block of the network that isn't a cable: a machine or Archive. */
    private boolean isBlock(BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MachineBlockEntity || level.getBlockEntity(pos) instanceof ArchiveBlockEntity;
    }

    /** Whether data passes between two touching blocks. */
    private boolean joins(BlockState from, BlockState to, BlockPos fromPos, BlockPos toPos) {
        Direction face = Direction.getApproximateNearest(toPos.getX() - fromPos.getX(), toPos.getY() - fromPos.getY(), toPos.getZ() - fromPos.getZ());
        if (level.getBlockEntity(fromPos) instanceof DataCableBlockEntity cable && cable.hasPart(face)
                || level.getBlockEntity(toPos) instanceof DataCableBlockEntity other && other.hasPart(face.getOpposite()))
            return false;
        if (DataCableBlock.coreless(from) || DataCableBlock.coreless(to)) return false;
        boolean fromCable = from.getBlock() instanceof DataCableBlock;
        boolean toCable = to.getBlock() instanceof DataCableBlock;
        if (fromCable && toCable) {
            return true;
        }
        return toCable || isBlock(toPos);
    }

    private void initializeNeighbourCables(BlockPos pos) {
        for (Direction side : Direction.values()) {
            initializeCables(pos.relative(side), pos);
        }
    }

    private record Wave(BlockPos pos, UUID owner, int distance) {}

    /** Old worlds have no cable claims. Use nearby terminals to establish their boundaries once. */
    private void initializeCables(BlockPos start, @Nullable BlockPos except) {
        CableClaims claims = CableClaims.get(level);
        if (!level.isLoaded(start) || !(level.getBlockState(start).getBlock() instanceof DataCableBlock)
                || claims.owner(level, start) != null || claims.blocked(level, start)) {
            return;
        }
        Set<BlockPos> cables = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && cables.size() < MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            if (!cables.add(pos)) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (!next.equals(except) && !cables.contains(next) && level.isLoaded(next)
                        && level.getBlockState(next).getBlock() instanceof DataCableBlock
                        && claims.owner(level, next) == null && !claims.blocked(level, next)
                        && joins(level.getBlockState(pos), level.getBlockState(next), pos, next)) {
                    queue.add(next);
                }
            }
        }
        List<Wave> preferred = new ArrayList<>();
        List<Wave> other = new ArrayList<>();
        for (BlockPos cable : cables) {
            for (Direction side : Direction.values()) {
                BlockPos next = cable.relative(side);
                UUID owner = ownerAt(next);
                if (next.equals(except) || owner == null || contestedMachine(next) || claims.blocked(level, next)
                        || !joins(level.getBlockState(cable), level.getBlockState(next), cable, next)) {
                    continue;
                }
                Wave seed = new Wave(cable, owner, 0);
                if (level.getBlockEntity(next) instanceof EncodingTerminalBlockEntity
                        || level.getBlockState(next).getBlock() instanceof DataCableBlock) {
                    preferred.add(seed);
                } else {
                    other.add(seed);
                }
            }
        }
        ArrayDeque<Wave> waves = new ArrayDeque<>(preferred.isEmpty() ? other : preferred);
        Map<BlockPos, Integer> distances = new HashMap<>();
        Map<BlockPos, Set<UUID>> owners = new HashMap<>();
        while (!waves.isEmpty()) {
            Wave wave = waves.poll();
            int distance = distances.getOrDefault(wave.pos(), Integer.MAX_VALUE);
            if (wave.distance() > distance) {
                continue;
            }
            if (wave.distance() < distance) {
                distances.put(wave.pos(), wave.distance());
                owners.put(wave.pos(), new HashSet<>());
            }
            if (!owners.get(wave.pos()).add(wave.owner())) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos next = wave.pos().relative(side);
                if (cables.contains(next) && joins(level.getBlockState(wave.pos()), level.getBlockState(next), wave.pos(), next)) {
                    waves.add(new Wave(next, wave.owner(), wave.distance() + 1));
                }
            }
        }
        owners.forEach((cable, nearest) -> claims.set(level, cable,
                nearest.size() == 1 ? nearest.iterator().next() : null, nearest.size() > 1));
        owners.keySet().forEach(this::refreshCableShapes);
        Set<BlockPos> machines = new HashSet<>();
        for (BlockPos cable : cables) {
            for (Direction side : Direction.values()) {
                BlockPos next = cable.relative(side);
                if (!next.equals(except) && isBlock(next) && !(level.getBlockEntity(next) instanceof EncodingTerminalBlockEntity)) {
                    machines.add(next);
                }
            }
        }
        for (BlockPos machine : machines) {
            Set<UUID> adjacent = neighbourOwners(machine);
            if (adjacent.size() == 1) {
                UUID owner = adjacent.iterator().next();
                adopt(machine, owner, nameFor(owner, machine));
            }
            setBlocked(machine, adjacent.size() > 1);
        }
    }

    private boolean ownerCompatible(BlockPos from, BlockPos to) {
        return compatible(this, from, this, to);
    }

    /** The cable rule, for two blocks in any dimensions: neither held back, and one owner or none between them. */
    private static boolean compatible(Networks a, BlockPos from, Networks b, BlockPos to) {
        if (a.blockedCable(from) || b.blockedCable(to)) {
            return false;
        }
        if (a.contestedMachine(from) || b.contestedMachine(to)) {
            return false;
        }
        UUID x = a.ownerAt(from);
        UUID y = b.ownerAt(to);
        return x == null || y == null || x.equals(y);
    }

    private boolean blockedCable(BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof DataCableBlock && CableClaims.get(level).blocked(level, pos);
    }

    private boolean contestedMachine(BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MachineBlockEntity machine && machine.networkBlocked()
                || level.getBlockEntity(pos) instanceof ArchiveBlockEntity archive && archive.networkBlocked();
    }

    private boolean refreshBlocked(BlockPos pos) {
        if (!isMember(pos)) {
            return false;
        }
        CableClaims claims = CableClaims.get(level);
        boolean cable = level.getBlockState(pos).getBlock() instanceof DataCableBlock;
        if (!(cable ? claims.blocked(level, pos) : contestedMachine(pos))) {
            return false;
        }
        Set<UUID> owners = neighbourOwners(pos);
        if (owners.size() > 1) {
            return false;
        }
        UUID owner = owners.isEmpty() ? null : owners.iterator().next();
        if (cable) {
            claims.set(level, pos, owner, false);
        } else {
            setBlocked(pos, false);
            if (owner != null) {
                adopt(pos, owner, nameFor(owner, pos));
            }
        }
        if (owner != null) {
            claimUnowned(pos, owner);
        }
        return true;
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Networks networks = LEVELS.get(level);
            if (networks != null) {
                networks.chunkChanged(event.getChunk().getPos().pack());
            }
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Networks networks = LEVELS.get(level);
            if (networks != null) {
                networks.chunkChanged(event.getChunk().getPos().pack());
            }
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        LINKS.clear();
    }
}
