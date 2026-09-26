package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Finds crafting networks and keeps them until something changes. A network is worked out the first time anything
 * asks about one of its blocks, by walking from block to block. Placing or removing a cable, a machine or an Archive,
 * or a chunk loading or unloading, throws every network of that level away; each is worked out again when next
 * asked, keeping the power its cables held.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Networks {
    /** Largest network walked; blocks beyond it are left out. */
    public static final int MAX_BLOCKS = 4_096;

    private static final Map<ServerLevel, Networks> LEVELS = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<BlockPos, CableNetwork> byPos = new HashMap<>();
    private final Set<CableNetwork> all = new LinkedHashSet<>();
    /** Power held by networks that were thrown away, by one of their blocks, until a new network picks it up. */
    private final Map<BlockPos, Integer> leftover = new HashMap<>();

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

    /** Something changed: every network in this level is worked out again when next needed. */
    public static void invalidate(ServerLevel level) {
        Networks networks = LEVELS.get(level);
        if (networks != null) {
            networks.clear();
        }
    }

    private void clear() {
        // Power not yet picked up from an earlier change stays waiting too, however often things change meanwhile.
        for (CableNetwork network : all) {
            int energy = network.buffer().getAmountAsInt();
            if (energy > 0 && !network.cables().isEmpty()) {
                leftover.merge(network.cables().iterator().next(), energy, Integer::sum);
            }
        }
        leftover.keySet().removeIf(pos -> level.isLoaded(pos) && !(level.getBlockState(pos).getBlock() instanceof DataCableBlock));
        all.clear();
        byPos.clear();
    }

    private @Nullable CableNetwork find(BlockPos pos) {
        CableNetwork known = byPos.get(pos);
        if (known != null) {
            return known;
        }
        if (!isMember(pos)) {
            return null;
        }
        Set<BlockPos> cables = new HashSet<>();
        Set<BlockPos> machines = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(pos.immutable());
        seen.add(pos.immutable());
        while (!queue.isEmpty() && seen.size() <= MAX_BLOCKS) {
            BlockPos at = queue.poll();
            BlockState state = level.getBlockState(at);
            boolean cable = state.getBlock() instanceof DataCableBlock;
            (cable ? cables : machines).add(at);
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (!seen.contains(next) && level.isLoaded(next) && joins(state, level.getBlockState(next), next)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        int energy = 0;
        for (BlockPos cable : cables) {
            Integer held = leftover.remove(cable);
            if (held != null) {
                energy += held;
            }
        }
        CableNetwork network = new CableNetwork(level, cables, machines, energy);
        all.add(network);
        cables.forEach(p -> byPos.put(p, network));
        machines.forEach(p -> byPos.put(p, network));
        return network;
    }

    private boolean isMember(BlockPos pos) {
        return level.isLoaded(pos) && (level.getBlockState(pos).getBlock() instanceof DataCableBlock || isBlock(pos));
    }

    /** A block of the network that isn't a cable: a crafting block, or an Archive (which only takes part in access). */
    private boolean isBlock(BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MachineBlockEntity || level.getBlockEntity(pos) instanceof ArchiveBlockEntity;
    }

    /** Whether data passes between two touching blocks. */
    private boolean joins(BlockState from, BlockState to, BlockPos toPos) {
        boolean fromCable = from.getBlock() instanceof DataCableBlock;
        boolean toCable = to.getBlock() instanceof DataCableBlock;
        if (fromCable && toCable) {
            DyeColor a = ((DataCableBlock) from.getBlock()).color();
            DyeColor b = ((DataCableBlock) to.getBlock()).color();
            return DataCableBlock.compatible(a, b);
        }
        return toCable || isBlock(toPos);
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Networks networks = LEVELS.get(level);
            if (networks != null) {
                for (CableNetwork network : Set.copyOf(networks.all)) {
                    network.tick();
                }
            }
        }
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            invalidate(level);
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            invalidate(level);
        }
    }
}
