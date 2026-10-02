package dev.micolash.jasm.network;

import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Which brain leads a network, and how many machines that lets the network hold. */
public final class NetworkLimit {
    private NetworkLimit() {}

    /** How many machines a network holds, how many it may, and which brain says so. Over the limit, it stops. */
    public record State(int count, int limit, @Nullable BlockPos leader, boolean stopped) {}

    /** Powered first, then highest level, then most progress, then the one placed first. */
    private static final Comparator<NetworkBrainBlockEntity> BEST = Comparator
            .comparingInt(NetworkBrainBlockEntity::shownLevel)
            .thenComparingLong(b -> b.progress().points())
            .thenComparing(Comparator.comparingLong(NetworkBrainBlockEntity::placedAt).reversed())
            .thenComparing(Comparator.comparingLong((NetworkBrainBlockEntity b) -> b.getBlockPos().asLong()).reversed());

    public static @Nullable NetworkBrainBlockEntity leader(List<NetworkBrainBlockEntity> brains) {
        return brains.stream().filter(NetworkBrainBlockEntity::working).max(BEST).orElse(null);
    }
}
