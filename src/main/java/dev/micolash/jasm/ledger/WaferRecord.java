package dev.micolash.jasm.ledger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.WaferContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Authoritative state of one wafer. Mutated only through {@link JasmLedger}. */
public final class WaferRecord {
    /** One stored variant and its count. */
    public record Entry(ItemResource item, long count) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                        ItemResource.CODEC.fieldOf("item").forGetter(Entry::item),
                        Codec.LONG.fieldOf("count").forGetter(Entry::count))
                .apply(i, Entry::new));
    }

    private static final LenientListCodec<Entry> CONTENTS_CODEC = new LenientListCodec<>(Entry.CODEC, "wafer entry");

    public static final Codec<WaferRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(WaferRecord::id),
                    Codec.INT.fieldOf("capacity").forGetter(WaferRecord::capacity),
                    LedgerCodecs.STAMP.fieldOf("current").forGetter(WaferRecord::current),
                    LedgerCodecs.STAMP.optionalFieldOf("recovery_floor").forGetter(r -> Optional.ofNullable(r.recoveryFloor)),
                    UUIDUtil.CODEC.optionalFieldOf("archive").forGetter(r -> Optional.ofNullable(r.archiveId)),
                    Codec.STRING.optionalFieldOf("last_known_name", "").forGetter(WaferRecord::lastKnownName),
                    CONTENTS_CODEC.fieldOf("contents").forGetter(WaferRecord::encodeContents))
            .apply(i, WaferRecord::decode));

    private final UUID id;
    private int capacity;
    private Stamp current;
    private @Nullable Stamp recoveryFloor;
    private @Nullable UUID archiveId;
    private String lastKnownName;
    private final WaferContents<ItemResource> contents = new WaferContents<>();
    private final List<Dynamic<?>> quarantined = new ArrayList<>();
    private long quarantinedCount;

    WaferRecord(UUID id, int capacity, Stamp current) {
        this.id = id;
        this.capacity = capacity;
        this.current = current;
        this.lastKnownName = "";
    }

    private static WaferRecord decode(UUID id, int capacity, Stamp current, Optional<Stamp> floor, Optional<UUID> archive,
            String name, LenientListCodec.Lenient<Entry> stored) {
        WaferRecord record = new WaferRecord(id, capacity, current);
        record.recoveryFloor = floor.orElse(null);
        record.archiveId = archive.orElse(null);
        record.lastKnownName = name;
        for (Entry entry : stored.values()) {
            if (!entry.item().isEmpty() && entry.count() > 0) {
                record.contents.putLoaded(entry.item(), entry.count());
            }
        }
        for (Dynamic<?> raw : stored.raw()) {
            record.quarantined.add(raw);
            record.quarantinedCount += Math.max(0, raw.get("count").asLong(0));
        }
        return record;
    }

    private LenientListCodec.Lenient<Entry> encodeContents() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<ItemResource, Long> e : contents.view().entrySet()) {
            entries.add(new Entry(e.getKey(), e.getValue()));
        }
        return new LenientListCodec.Lenient<>(entries, List.copyOf(quarantined));
    }

    public UUID id() {
        return id;
    }

    public int capacity() {
        return capacity;
    }

    public Stamp current() {
        return current;
    }

    public @Nullable Stamp recoveryFloor() {
        return recoveryFloor;
    }

    public @Nullable UUID archiveId() {
        return archiveId;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    /** Read-only view of decodable contents. */
    public Map<ItemResource, Long> contents() {
        return contents.view();
    }

    public long count(ItemResource key) {
        return contents.count(key);
    }

    public long quarantinedCount() {
        return quarantinedCount;
    }

    /** Items counted against capacity, including quarantined ones. */
    public long used() {
        return contents.total() + quarantinedCount;
    }

    public long free() {
        return Math.max(0, capacity - used());
    }

    public boolean isEmpty() {
        return used() == 0;
    }

    public StampPolicy.RecordView view() {
        return new StampPolicy.RecordView(current, recoveryFloor, capacity);
    }

    // --- package-private mutators, called only by JasmLedger ---

    WaferContents<ItemResource> mutableContents() {
        return contents;
    }

    /** Capacity available to decodable contents (quarantined entries keep their share). */
    long contentCapacity() {
        return Math.max(0, capacity - quarantinedCount);
    }

    void setCurrent(Stamp stamp) {
        this.current = stamp;
    }

    void setRecoveryFloor(Stamp stamp) {
        this.recoveryFloor = stamp;
    }

    void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    void setArchiveId(@Nullable UUID archiveId) {
        this.archiveId = archiveId;
    }

    void setLastKnownName(String name) {
        this.lastKnownName = name;
    }
}
