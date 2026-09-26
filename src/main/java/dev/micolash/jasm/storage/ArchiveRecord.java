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
                    Codec.LONG.listOf().optionalFieldOf("linked", List.of()).forGetter(r -> List.copyOf(r.linked)))
            .apply(i, ArchiveRecord::decode));

    private final UUID id;
    private ArchiveTier tier;
    private final UUID owner;
    private final String ownerName;
    private @Nullable Placement placement;
    /**
     * Serials of wafers linked here, oldest first. The wafer record's own link is what counts: an entry whose record
     * now points elsewhere is dropped when the list is read.
     */
    private final Set<Long> linked = new LinkedHashSet<>();

    ArchiveRecord(UUID id, ArchiveTier tier, UUID owner, String ownerName) {
        this.id = id;
        this.tier = tier;
        this.owner = owner;
        this.ownerName = ownerName;
    }

    private static ArchiveRecord decode(UUID id, ArchiveTier tier, UUID owner, String ownerName, Optional<Placement> placement,
            List<Long> linked) {
        ArchiveRecord record = new ArchiveRecord(id, tier, owner, ownerName);
        record.placement = placement.orElse(null);
        record.linked.addAll(linked);
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

    // --- package-private mutators, called only by JasmState ---

    void setTier(ArchiveTier tier) {
        this.tier = tier;
    }

    void setPlacement(@Nullable Placement placement) {
        this.placement = placement;
    }

    boolean addLinked(long serial) {
        return linked.add(serial);
    }

    boolean removeLinked(long serial) {
        return linked.remove(serial);
    }
}
