package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.archive.ArchiveTier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Stored state of one Archive: owner, where it stands, and which wafers are linked to it. Saves from before access
 * moved to the Encoding Terminal also list trusted players; that list is no longer read.
 */
public final class ArchiveRecord {
    /** Where the Archive block currently stands. Absent while carried as an item. */
    public record Placement(ResourceKey<Level> dimension, BlockPos pos) {
        public static final Codec<Placement> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Placement::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Placement::pos))
                .apply(i, Placement::new));
    }

    public static final Codec<ArchiveRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ArchiveRecord::id),
            ArchiveTier.CODEC.fieldOf("tier").forGetter(ArchiveRecord::tier),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(ArchiveRecord::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(ArchiveRecord::ownerName),
            Placement.CODEC.optionalFieldOf("placement").forGetter(r -> Optional.ofNullable(r.placement)),
            Codec.LONG.listOf().optionalFieldOf("linked", List.of()).forGetter(r -> List.copyOf(r.linked)),
            Codec.LONG.listOf().optionalFieldOf("discarded", List.of()).forGetter(r -> List.copyOf(r.discarded)),
            UUIDUtil.CODEC.optionalFieldOf("deck").forGetter(r -> Optional.ofNullable(r.deck)),
            UUIDUtil.CODEC.optionalFieldOf("deck_player").forGetter(r -> Optional.ofNullable(r.deckPlayer)),
            Codec.BOOL.optionalFieldOf("default_deck", true).forGetter(ArchiveRecord::defaultDeck),
            UUIDUtil.CODEC.optionalFieldOf("network").forGetter(r -> Optional.ofNullable(r.network)))
            .apply(i, ArchiveRecord::decode));

    private final UUID id;
    private ArchiveTier tier;
    private UUID owner;
    private String ownerName;
    private @Nullable Placement placement;
    /**
     * Serials of wafers linked here, oldest first. The wafer record's own link is what counts: an entry whose record
     * now points elsewhere is dropped when the list is read.
     */
    private final Set<Long> linked = new LinkedHashSet<>();
    private final Set<Long> discarded = new LinkedHashSet<>();
    private @Nullable UUID deck;
    private @Nullable UUID deckPlayer;
    private boolean defaultDeck = true;
    private @Nullable UUID network;

    ArchiveRecord(UUID id, ArchiveTier tier, UUID owner, String ownerName) {
        this.id = id;
        this.tier = tier;
        this.owner = owner;
        this.ownerName = ownerName;
    }

    private static ArchiveRecord decode(UUID id, ArchiveTier tier, UUID owner, String ownerName, Optional<Placement> placement,
            List<Long> linked, List<Long> discarded, Optional<UUID> deck, Optional<UUID> deckPlayer, boolean defaultDeck, Optional<UUID> network) {
        ArchiveRecord record = new ArchiveRecord(id, tier, owner, ownerName);
        record.placement = placement.orElse(null);
        record.linked.addAll(linked);
        record.discarded.addAll(discarded);
        record.deck = deck.orElse(null);
        record.deckPlayer = deckPlayer.orElse(null);
        record.defaultDeck = defaultDeck;
        record.network = network.orElse(null);
        return record;
    }

    public UUID id() {
        return id;
    }

    public ArchiveTier tier() {
        return tier;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public @Nullable Placement placement() {
        return placement;
    }

    public Set<Long> linked() {
        return Collections.unmodifiableSet(linked);
    }

    public boolean discarded(long serial) {
        return discarded.contains(serial);
    }

    public @Nullable UUID deck() {
        return deck;
    }

    public @Nullable UUID deckPlayer() {
        return deckPlayer;
    }

    public boolean defaultDeck() {
        return defaultDeck;
    }

    public @Nullable UUID network() {
        return network;
    }

    void setNetwork(@Nullable UUID network) {
        this.network = network;
    }

    void setDeck(@Nullable UUID deck, @Nullable UUID player, boolean defaultDeck) {
        this.deck = deck;
        this.deckPlayer = player;
        this.defaultDeck = defaultDeck;
    }

    void setOwner(UUID owner, String name) {
        this.owner = owner;
        this.ownerName = name;
    }

    void discardLinks() {
        discarded.addAll(linked);
        linked.clear();
    }

    // --- package-private mutators, called only by JasmState ---

    void setTier(ArchiveTier tier) {
        this.tier = tier;
    }

    void setPlacement(@Nullable Placement placement) {
        this.placement = placement;
    }

    boolean addLinked(long serial) {
        discarded.remove(serial);
        return linked.add(serial);
    }

    boolean removeLinked(long serial) {
        return linked.remove(serial);
    }
}
