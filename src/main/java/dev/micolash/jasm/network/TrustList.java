package dev.micolash.jasm.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** The players an Encoding Terminal's owner trusts with their crafting network, in the order they were added. */
public record TrustList(List<Entry> entries) {
    public record Entry(UUID id, String name) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
                        Codec.STRING.fieldOf("name").forGetter(Entry::name))
                .apply(i, Entry::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                Entry::new);
    }

    /** Most players one terminal can trust. */
    public static final int MAX = 32;
    public static final TrustList EMPTY = new TrustList(List.of());
    public static final Codec<TrustList> CODEC = Entry.CODEC.listOf().xmap(TrustList::new, TrustList::entries);
    public static final StreamCodec<RegistryFriendlyByteBuf, TrustList> STREAM_CODEC =
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)).map(TrustList::new, TrustList::entries);

    public TrustList {
        Map<UUID, Entry> unique = new LinkedHashMap<>();
        entries.stream().limit(MAX).forEach(e -> unique.putIfAbsent(e.id(), e));
        entries = List.copyOf(unique.values());
    }

    public boolean contains(UUID player) {
        return entries.stream().anyMatch(e -> e.id().equals(player));
    }

    public boolean isFull() {
        return entries.size() >= MAX;
    }

    public TrustList with(UUID player, String name) {
        List<Entry> updated = new ArrayList<>(entries);
        updated.removeIf(e -> e.id().equals(player));
        updated.add(new Entry(player, name));
        return new TrustList(updated);
    }

    public TrustList without(UUID player) {
        List<Entry> updated = new ArrayList<>(entries);
        updated.removeIf(e -> e.id().equals(player));
        return new TrustList(updated);
    }
}
