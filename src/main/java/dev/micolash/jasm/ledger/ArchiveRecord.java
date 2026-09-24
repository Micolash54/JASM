package dev.micolash.jasm.ledger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.archive.ArchiveTier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/** Authoritative state of one Archive. Placement and trust logic arrive with the Archive milestone. */
public final class ArchiveRecord {
    /** Where the Archive block currently stands. Absent while carried as an item. */
    public record Placement(ResourceKey<Level> dimension, BlockPos pos) {
        public static final Codec<Placement> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Placement::dimension),
                        BlockPos.CODEC.fieldOf("pos").forGetter(Placement::pos))
                .apply(i, Placement::new));
    }

    private record TrustedEntry(UUID id, String name) {
        static final Codec<TrustedEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(TrustedEntry::id),
                        Codec.STRING.fieldOf("name").forGetter(TrustedEntry::name))
                .apply(i, TrustedEntry::new));
    }

    public static final Codec<ArchiveRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(ArchiveRecord::id),
                    ArchiveTier.CODEC.fieldOf("tier").forGetter(ArchiveRecord::tier),
                    UUIDUtil.CODEC.fieldOf("owner").forGetter(ArchiveRecord::owner),
                    Codec.STRING.fieldOf("owner_name").forGetter(ArchiveRecord::ownerName),
                    TrustedEntry.CODEC.listOf().fieldOf("trusted").forGetter(ArchiveRecord::trustedEntries),
                    Placement.CODEC.optionalFieldOf("placement").forGetter(r -> Optional.ofNullable(r.placement)))
            .apply(i, ArchiveRecord::decode));

    private final UUID id;
    private final ArchiveTier tier;
    private final UUID owner;
    private final String ownerName;
    private final Map<UUID, String> trusted = new LinkedHashMap<>();
    private @Nullable Placement placement;

    ArchiveRecord(UUID id, ArchiveTier tier, UUID owner, String ownerName) {
        this.id = id;
        this.tier = tier;
        this.owner = owner;
        this.ownerName = ownerName;
    }

    private static ArchiveRecord decode(UUID id, ArchiveTier tier, UUID owner, String ownerName, List<TrustedEntry> trusted,
            Optional<Placement> placement) {
        ArchiveRecord record = new ArchiveRecord(id, tier, owner, ownerName);
        trusted.forEach(t -> record.trusted.put(t.id(), t.name()));
        record.placement = placement.orElse(null);
        return record;
    }

    private List<TrustedEntry> trustedEntries() {
        return trusted.entrySet().stream().map(e -> new TrustedEntry(e.getKey(), e.getValue())).toList();
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

    public Map<UUID, String> trusted() {
        return Collections.unmodifiableMap(trusted);
    }

    public @Nullable Placement placement() {
        return placement;
    }

    public boolean isAuthorized(UUID player) {
        return owner.equals(player) || trusted.containsKey(player);
    }

    // --- package-private mutators, called only by JasmLedger ---

    void setPlacement(@Nullable Placement placement) {
        this.placement = placement;
    }

    void putTrusted(UUID id, String name) {
        trusted.put(id, name);
    }

    void removeTrusted(UUID id) {
        trusted.remove(id);
    }
}
