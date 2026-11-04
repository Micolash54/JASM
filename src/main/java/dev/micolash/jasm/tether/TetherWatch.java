package dev.micolash.jasm.tether;

import dev.micolash.jasm.chunkload.ChunkLoaders.ChunkKey;
import dev.micolash.jasm.network.CableNetwork;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What the Tethers on one network share, worked out once for each network: every chunk it touches, and which Tether
 * keeps watch this tick. Lives as long as its network, which is replaced whenever something on it changes.
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

    /** The Tether that keeps watch this tick: the first placed among those whose operator can pay for every chunk. */
    @Nullable NetworkTetherBlockEntity leader(long now) {
        if (now != leaderTick) {
            leaderTick = now;
            leader = null;
            for (NetworkTetherBlockEntity tether : network.machines(NetworkTetherBlockEntity.class)) {
                if (!tether.isRemoved() && tether.canLead(chunks.size()) && (leader == null || FIRST.compare(tether, leader) < 0)) {
                    leader = tether;
                }
            }
        }
        return leader;
    }
}
