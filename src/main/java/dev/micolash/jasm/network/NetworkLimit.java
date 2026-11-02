package dev.micolash.jasm.network;

import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Which brain leads a network, and how many machines that lets the network hold. */
public final class NetworkLimit {
    private NetworkLimit() {}

    /** How many machines a network holds, how many it may, and which brain says so. Over the limit, it stops. */
    public record State(int count, int limit, @Nullable NetworkNode leader, boolean stopped) {}

    /** The most floors, then the one placed first, then by position and dimension so the pick never changes. */
    private static final Comparator<NetworkBrainBlockEntity> BEST = Comparator
            .comparingInt(NetworkBrainBlockEntity::floors)
            .thenComparing(Comparator.comparingLong(NetworkBrainBlockEntity::placedAt).reversed())
            .thenComparing(Comparator.comparingLong((NetworkBrainBlockEntity b) -> b.getBlockPos().asLong()).reversed())
            .thenComparing(Comparator.comparing((NetworkBrainBlockEntity b) -> b.getLevel().dimension().identifier().toString()).reversed());

    /** The best working brain that speaks for its tower; the brains above a tower's lowest floor never lead. */
    public static @Nullable NetworkBrainBlockEntity leader(List<NetworkBrainBlockEntity> brains) {
        return brains.stream().filter(b -> b.leadsItsTower() && b.working()).max(BEST).orElse(null);
    }
}
