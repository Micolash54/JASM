package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Finds crafting networks and keeps them until something changes. A network is worked out the first time anything
 * asks about one of its blocks, by walking from block to block. Placing or removing a cable, a machine or an Archive
 * throws away the network it touches, and only that one; a chunk loading or unloading throws away the networks that
 * have a block in it, or that stopped at its edge. Each is worked out again when next asked, keeping the power its
 * cables held.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Networks {
    /** Largest network walked; blocks beyond it are left out. */
    public static final int MAX_BLOCKS = 4_096;

    private static final Map<ServerLevel, Networks> LEVELS = new WeakHashMap<>();

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
        Set<BlockPos> previous = new HashSet<>();
        previous.add(pos.immutable());
        for (Direction side : Direction.values()) {
            previous.add(pos.relative(side));
        }
        for (BlockPos at : List.copyOf(previous)) {
            CableNetwork network = byPos.get(at);
            if (network != null) {
                previous.addAll(network.cables());
                previous.addAll(network.machines());
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

    private void drop(CableNetwork network) {
        if (!all.remove(network)) {
            return;
        }
        for (BlockPos pos : network.cables()) {
            byPos.remove(pos, network);
        }
        for (BlockPos pos : network.machines()) {
            byPos.remove(pos, network);
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
        known = byPos.get(pos);
        if (known != null) {
            return known;
        }
        Set<BlockPos> cables = new HashSet<>();
        Set<BlockPos> machines = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        queue.add(pos.immutable());
        seen.add(pos.immutable());
        boolean complete = true;
        while (!queue.isEmpty() && seen.size() <= MAX_BLOCKS) {
            BlockPos at = queue.poll();
            BlockState state = level.getBlockState(at);
            boolean cable = state.getBlock() instanceof DataCableBlock;
            (cable ? cables : machines).add(at);
            chunks.add(ChunkPos.pack(at));
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (!level.isLoaded(next)) {
                    // The walk stops here; when that chunk comes in, this network is worked out again.
                    complete = false;
                    chunks.add(ChunkPos.pack(next));
                }
                if (!seen.contains(next) && level.isLoaded(next) && joins(state, level.getBlockState(next), at, next)
                        && ownerCompatible(at, next)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        CableNetwork network = new CableNetwork(level, cables, machines, complete && queue.isEmpty());
        all.add(network);
        cables.forEach(p -> byPos.put(p, network));
        machines.forEach(p -> byPos.put(p, network));
        chunksOf.put(network, chunks);
        for (long key : chunks) {
            byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(network);
        }
        AutocraftState.get(level.getServer()).mergePairings(network.machines(EncodingTerminalBlockEntity.class).stream()
                .map(EncodingTerminalBlockEntity::ensureId).toList());
        return network;
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
        if (level.getBlockEntity(fromPos) instanceof DataCableBlockEntity cable && cable.port(face) != null
                || level.getBlockEntity(toPos) instanceof DataCableBlockEntity other && other.port(face.getOpposite()) != null)
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
        CableClaims claims = CableClaims.get(level);
        if (level.getBlockState(from).getBlock() instanceof DataCableBlock && claims.blocked(level, from)
                || level.getBlockState(to).getBlock() instanceof DataCableBlock && claims.blocked(level, to)) {
            return false;
        }
        if (contestedMachine(from) || contestedMachine(to)) {
            return false;
        }
        UUID a = ownerAt(from);
        UUID b = ownerAt(to);
        return a == null || b == null || a.equals(b);
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
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Networks networks = LEVELS.get(level);
            if (networks != null) {
                for (CableNetwork network : new ArrayList<>(networks.all)) {
                    network.tick();
                }
            }
        }
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
}
