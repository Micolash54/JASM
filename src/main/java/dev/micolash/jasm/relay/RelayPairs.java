package dev.micolash.jasm.relay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.NetworkNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Which Relay Chatterboxes hold which frequency, kept with the world: for each owner's frequency, the two ends that
 * hold it, whether those two have started their link, and the ends waiting for a turn, longest first. An end is where
 * its lower half stands. Nothing here is trusted on its own: an end only counts while its block is loaded and still
 * says the same frequency and owner. A world saved before this existed starts with no pairs.
 */
public final class RelayPairs extends SavedData {
    /** One owner's frequency. Two owners never share one. */
    record Channel(@Nullable UUID owner, int frequency) {}

    /** Who holds one frequency. */
    static final class Pair {
        final List<NetworkNode> ends = new ArrayList<>(2);
        final List<NetworkNode> waiting = new ArrayList<>();
        /** Whether these two ends started their link; they reconnect on their own until one of them changes. */
        boolean established;
    }

    private record Saved(Optional<UUID> owner, int frequency, List<NetworkNode> ends, boolean established, List<NetworkNode> waiting) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Saved::owner),
                Codec.INT.fieldOf("frequency").forGetter(Saved::frequency),
                NetworkNode.CODEC.listOf().optionalFieldOf("ends", List.of()).forGetter(Saved::ends),
                Codec.BOOL.optionalFieldOf("established", false).forGetter(Saved::established),
                NetworkNode.CODEC.listOf().optionalFieldOf("waiting", List.of()).forGetter(Saved::waiting))
                .apply(i, Saved::new));
    }

    public static final Codec<RelayPairs> CODEC = RecordCodecBuilder.create(i -> i.group(
            Saved.CODEC.listOf().optionalFieldOf("pairs", List.of()).forGetter(RelayPairs::saved))
            .apply(i, RelayPairs::new));
    public static final SavedDataType<RelayPairs> TYPE = new SavedDataType<>(Jasm.id("relay_pairs"), RelayPairs::new, CODEC);

    private record ChunkKey(ResourceKey<Level> dimension, long chunk) {
        static ChunkKey of(NetworkNode node) {
            return new ChunkKey(node.dimension(), ChunkPos.pack(node.pos()));
        }
    }

    private final Map<Channel, Pair> pairs = new LinkedHashMap<>();
    private final Map<NetworkNode, Channel> where = new HashMap<>();
    private final Map<ChunkKey, Set<NetworkNode>> byChunk = new HashMap<>();

    public RelayPairs() {}

    /** Reads a save, dropping what can't be right: a frequency out of range, an end listed twice, more than two ends. */
    private RelayPairs(List<Saved> saved) {
        for (Saved entry : saved) {
            if (entry.frequency() < 0 || entry.frequency() > RelayEnd.MAX_FREQUENCY) {
                continue;
            }
            Channel channel = new Channel(entry.owner().orElse(null), entry.frequency());
            List<NetworkNode> ends = entry.ends().stream().filter(n -> !where.containsKey(n)).distinct().toList();
            for (NetworkNode node : ends) {
                place(node, channel);
            }
            for (NetworkNode node : entry.waiting()) {
                if (!where.containsKey(node)) {
                    place(node, channel);
                }
            }
            Pair pair = pairs.get(channel);
            if (pair != null && pair.ends.equals(entry.ends())) {
                pair.established = entry.established();
            }
        }
    }

    private List<Saved> saved() {
        List<Saved> out = new ArrayList<>(pairs.size());
        pairs.forEach((channel, pair) -> out.add(new Saved(Optional.ofNullable(channel.owner()), channel.frequency(),
                List.copyOf(pair.ends), pair.established, List.copyOf(pair.waiting))));
        return out;
    }

    static RelayPairs get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The frequency {@code node} holds or waits for, or null. */
    @Nullable Channel channelOf(NetworkNode node) {
        return where.get(node);
    }

    /** The two ends holding {@code channel}, at most, in the order they took it. */
    List<NetworkNode> ends(Channel channel) {
        Pair pair = pairs.get(channel);
        return pair == null ? List.of() : List.copyOf(pair.ends);
    }

    /** The ends waiting for {@code channel}, longest first. */
    List<NetworkNode> waiting(Channel channel) {
        Pair pair = pairs.get(channel);
        return pair == null ? List.of() : List.copyOf(pair.waiting);
    }

    /** Whether the two ends of {@code channel} have started their link. */
    boolean established(Channel channel) {
        Pair pair = pairs.get(channel);
        return pair != null && pair.established;
    }

    /** Every end that holds or waits for a frequency in this chunk. */
    Set<NetworkNode> in(ResourceKey<Level> dimension, long chunk) {
        Set<NetworkNode> found = byChunk.get(new ChunkKey(dimension, chunk));
        return found == null ? Set.of() : Set.copyOf(found);
    }

    /** {@code node}, on no frequency yet, takes a free place on {@code channel} or joins the back of its queue. */
    void join(NetworkNode node, Channel channel) {
        place(node, channel);
        setDirty();
    }

    private void place(NetworkNode node, Channel channel) {
        Pair pair = pairs.computeIfAbsent(channel, k -> new Pair());
        if (pair.ends.size() < 2) {
            pair.ends.add(node);
            pair.established = false;
        } else {
            pair.waiting.add(node);
        }
        where.put(node, channel);
        byChunk.computeIfAbsent(ChunkKey.of(node), k -> new HashSet<>()).add(node);
    }

    /** Takes {@code node} off its frequency. Returns the frequency if it was one of the two ends, so its place is free. */
    @Nullable Channel leave(NetworkNode node) {
        Channel channel = where.remove(node);
        if (channel == null) {
            return null;
        }
        Set<NetworkNode> chunk = byChunk.get(ChunkKey.of(node));
        if (chunk != null && chunk.remove(node) && chunk.isEmpty()) {
            byChunk.remove(ChunkKey.of(node));
        }
        Pair pair = pairs.get(channel);
        boolean end = pair.ends.remove(node);
        pair.waiting.remove(node);
        if (end) {
            pair.established = false;
        }
        if (pair.ends.isEmpty() && pair.waiting.isEmpty()) {
            pairs.remove(channel);
        }
        setDirty();
        return end ? channel : null;
    }

    /** A waiting end takes the free place on its frequency. */
    void promote(NetworkNode node) {
        Pair pair = pairs.get(where.get(node));
        if (pair.ends.size() < 2 && pair.waiting.remove(node)) {
            pair.ends.add(node);
            pair.established = false;
            setDirty();
        }
    }

    void setEstablished(Channel channel, boolean established) {
        Pair pair = pairs.get(channel);
        if (pair != null && pair.established != established) {
            pair.established = established;
            setDirty();
        }
    }
}
