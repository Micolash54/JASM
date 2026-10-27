package dev.micolash.jasm.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Every Encoding Terminal's trust list, kept with the world so it can be read while the terminal's area isn't loaded.
 * Keyed by the terminal's identity, the one paired Decks carry. Each entry also remembers the owner and which of the
 * owner's other terminals stood on the same network when it was last seen whole, so trust there still counts.
 * A terminal that stands nowhere and has no Deck paired to it is forgotten at server start: its mined item carries
 * the list.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class NetworkTrust extends SavedData {
    public record Entry(Optional<UUID> owner, TrustList trust, List<UUID> network) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Entry::owner),
                TrustList.CODEC.optionalFieldOf("trust", TrustList.EMPTY).forGetter(Entry::trust),
                UUIDUtil.CODEC.listOf().optionalFieldOf("network", List.of()).forGetter(Entry::network))
                .apply(i, Entry::new));

        static final Entry NEW = new Entry(Optional.empty(), TrustList.EMPTY, List.of());

        public Entry {
            network = List.copyOf(network);
        }

        boolean ownedBy(UUID player) {
            return owner.isPresent() && owner.get().equals(player);
        }
    }

    public static final Codec<NetworkTrust> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).optionalFieldOf("terminals", Map.of())
                    .forGetter(s -> Map.copyOf(s.entries)))
            .apply(i, NetworkTrust::new));

    public static final SavedDataType<NetworkTrust> TYPE = new SavedDataType<>(Jasm.id("network_trust"), NetworkTrust::new, CODEC);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public NetworkTrust() {}

    private NetworkTrust(Map<UUID, Entry> entries) {
        this.entries.putAll(entries);
    }

    public static NetworkTrust get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Whether the terminal has been seen since trust moved here. One that hasn't still keeps its list in its block. */
    public boolean known(UUID terminal) {
        return entries.containsKey(terminal);
    }

    public Optional<Entry> entry(UUID terminal) {
        return Optional.ofNullable(entries.get(terminal));
    }

    public TrustList trust(UUID terminal) {
        Entry entry = entries.get(terminal);
        return entry == null ? TrustList.EMPTY : entry.trust();
    }

    public void setTrust(UUID terminal, TrustList trust) {
        Entry old = entries.getOrDefault(terminal, Entry.NEW);
        put(terminal, new Entry(old.owner(), trust, old.network()));
    }

    /** Records the terminal's owner (none yet changes nothing); a new owner starts with nobody trusted. */
    public void setOwner(UUID terminal, @Nullable UUID owner) {
        Entry old = entries.getOrDefault(terminal, Entry.NEW);
        if (owner == null) {
            put(terminal, old);
            return;
        }
        boolean changed = old.owner().isPresent() && !old.owner().get().equals(owner);
        put(terminal, new Entry(Optional.of(owner), changed ? TrustList.EMPTY : old.trust(), old.network()));
    }

    /**
     * Moves a list the terminal brought with it (from an older save, or on its mined item) in here, keeping every
     * name already here as well.
     */
    public void bringIn(UUID terminal, @Nullable UUID owner, TrustList carried) {
        Entry old = entries.getOrDefault(terminal, Entry.NEW);
        TrustList merged = old.trust();
        for (TrustList.Entry name : carried.entries()) {
            if (!merged.contains(name.id()) && !Objects.equals(owner, name.id())) merged = merged.with(name.id(), name.name());
        }
        put(terminal, new Entry(owner == null ? old.owner() : Optional.of(owner), merged, old.network()));
    }

    /**
     * The terminals {@code owners} (identity to owner) stood together on one network, seen whole. Each remembers the
     * others with its owner.
     */
    public void sawNetwork(Map<UUID, UUID> owners) {
        for (Map.Entry<UUID, UUID> terminal : owners.entrySet()) {
            List<UUID> same = owners.entrySet().stream()
                    .filter(o -> !o.getKey().equals(terminal.getKey()) && o.getValue().equals(terminal.getValue()))
                    .map(Map.Entry::getKey).sorted().toList();
            Entry old = entries.getOrDefault(terminal.getKey(), Entry.NEW);
            put(terminal.getKey(), new Entry(Optional.of(terminal.getValue()), old.owner().isPresent()
                    && !old.owner().get().equals(terminal.getValue()) ? TrustList.EMPTY : old.trust(), same));
        }
    }

    /**
     * Whether {@code player} owns the terminal or is trusted on its network, from what was last seen: its own list, or
     * the list of another of its owner's terminals that stood on the same network (both remembering each other).
     */
    public boolean trusts(UUID terminal, UUID player) {
        Entry entry = entries.get(terminal);
        if (entry == null || entry.owner().isEmpty()) return false;
        if (entry.ownedBy(player) || entry.trust().contains(player)) return true;
        for (UUID other : entry.network()) {
            Entry sibling = entries.get(other);
            if (sibling != null && sibling.owner().equals(entry.owner()) && sibling.network().contains(terminal)
                    && sibling.trust().contains(player)) {
                return true;
            }
        }
        return false;
    }

    /** A copy of a terminal that became a terminal of its own starts with the list it was copied with. */
    public void copy(UUID from, UUID to) {
        Entry old = entries.get(from);
        if (old != null) put(to, new Entry(old.owner(), old.trust(), List.of()));
    }

    /** Starts writing this file without waiting for the disk, the way {@link AutocraftState#saveSoon} does. */
    public void saveSoon(MinecraftServer server) {
        if (isDirty()) {
            WaferStore.get(server).writeAfter(server.getDataStorage().scheduleSave());
        }
    }

    private void put(UUID terminal, Entry entry) {
        if (!entry.equals(entries.put(terminal, entry))) setDirty();
    }

    /** Forgets terminals that stand nowhere and have no Deck paired to them; their mined items carry their lists. */
    void sweep(AutocraftState state) {
        Set<UUID> keep = new HashSet<>();
        state.pairings().forEach(p -> keep.add(p.terminal()));
        if (entries.keySet().removeIf(id -> !keep.contains(id) && state.terminal(id).isEmpty())) setDirty();
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        get(server).sweep(AutocraftState.get(server));
    }
}
