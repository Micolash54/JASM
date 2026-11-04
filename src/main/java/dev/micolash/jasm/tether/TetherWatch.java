package dev.micolash.jasm.tether;

import dev.micolash.jasm.chunkload.ChunkLoaders.ChunkKey;
import dev.micolash.jasm.network.CableNetwork;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What the Tethers on one network share, worked out once for each network: every chunk it touches, the ones its Tether
 * holds under the server's limit, and which Tether keeps watch this tick. Lives as long as its network, which is
 * replaced whenever something on it changes.
 */
final class TetherWatch {
    static final Function<CableNetwork, TetherWatch> OF = TetherWatch::new;

    /** The one placed first leads, then by position and dimension, so the pick never changes on its own. */
    private static final Comparator<NetworkTetherBlockEntity> FIRST = Comparator
            .comparingLong(NetworkTetherBlockEntity::placedAt)
            .thenComparingLong(t -> t.getBlockPos().asLong())
            .thenComparing(t -> t.getLevel() == null ? "" : t.getLevel().dimension().identifier().toString());

    private final CableNetwork network;
    private final Set<ChunkKey> chunks;
    private long leaderTick = Long.MIN_VALUE;
    private @Nullable NetworkTetherBlockEntity leader;
    /** The chunks last picked under a limit, and for which Tether and limit. */
    private @Nullable Set<ChunkKey> nearest;
    private @Nullable ResourceKey<Level> nearestDimension;
    private long nearestPos;
    private int nearestLimit;

    private TetherWatch(CableNetwork network) {
        this.network = network;
        Set<ChunkKey> found = new LinkedHashSet<>();
        for (CableNetwork.Part part : network.parts()) {
            ResourceKey<Level> dimension = part.level().dimension();
            for (BlockPos pos : part.cables()) found.add(new ChunkKey(dimension, ChunkPos.containing(pos).pack()));
            for (BlockPos pos : part.machines()) found.add(new ChunkKey(dimension, ChunkPos.containing(pos).pack()));
        }
        chunks = Collections.unmodifiableSet(found);
    }

    /** Every chunk with a cable or machine of the network in it, in every dimension it stands in. */
    Set<ChunkKey> chunks() {
        return chunks;
    }

    /** How many chunks the leading Tether holds under a limit of {@code limit} chunks, 0 being none. */
    int held(int limit) {
        return limit > 0 ? Math.min(chunks.size(), limit) : chunks.size();
    }

    /**
     * The chunks a Tether at {@code pos} holds under a limit of {@code limit} chunks, 0 being none: all of them, or the
     * nearest first, those in its own dimension before the rest. Ties go by position, so the pick stays the same.
     */
    Set<ChunkKey> wanted(ResourceKey<Level> dimension, BlockPos pos, int limit) {
        if (limit <= 0 || chunks.size() <= limit) return chunks;
        if (nearest != null && nearestLimit == limit && nearestPos == pos.asLong() && dimension.equals(nearestDimension)) return nearest;
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        List<ChunkKey> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.<ChunkKey>comparingInt(key -> key.dimension().equals(dimension) ? 0 : 1)
                .thenComparingLong(key -> distance(key.chunk(), x, z))
                .thenComparing(key -> key.dimension().identifier().toString())
                .thenComparingLong(ChunkKey::chunk));
        nearest = Collections.unmodifiableSet(new LinkedHashSet<>(sorted.subList(0, limit)));
        nearestDimension = dimension;
        nearestPos = pos.asLong();
        nearestLimit = limit;
        return nearest;
    }

    private static long distance(long chunk, int x, int z) {
        long dx = ChunkPos.getX(chunk) - x;
        long dz = ChunkPos.getZ(chunk) - z;
        return dx * dx + dz * dz;
    }

    /**
     * The Tether that keeps watch this tick: the first placed among those whose operator can pay for the
     * {@code held} chunks the leader holds.
     */
    @Nullable NetworkTetherBlockEntity leader(long now, int held) {
        if (now != leaderTick) {
            leaderTick = now;
            leader = null;
            for (NetworkTetherBlockEntity tether : network.machines(NetworkTetherBlockEntity.class)) {
                if (!tether.isRemoved() && tether.canLead(held) && (leader == null || FIRST.compare(tether, leader) < 0)) {
                    leader = tether;
                }
            }
        }
        return leader;
    }
}
