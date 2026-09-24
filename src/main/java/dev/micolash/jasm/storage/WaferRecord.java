package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.WaferContents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Stored state of one wafer. Changed only through {@link WaferStore}.
 *
 * <p>Two stamps are tracked: {@code current} is the newest one handed out, {@code confirmed} the newest one known
 * to be on disk inside a saved player file. Only {@code confirmed} is ever written, so the saved stamp can never
 * be ahead of a wafer that really exists on disk.
 */
public final class WaferRecord {
    /** One stored variant and its count. */
    public record Entry(ItemResource item, long count) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                        ItemResource.CODEC.fieldOf("item").forGetter(Entry::item),
                        Codec.LONG.fieldOf("count").forGetter(Entry::count))
                .apply(i, Entry::new));
    }

    /** An entry that cannot be saved keeps its item name and count, so it still takes up space and shows up for admins. */
    private static final LenientListCodec<Entry> CONTENTS_CODEC = new LenientListCodec<>(Entry.CODEC, "wafer entry",
            new LenientListCodec.Placeholder<>() {
                @Override
                public <T> T encode(Entry entry, String error, DynamicOps<T> ops) {
                    return ops.createMap(Map.of(
                            ops.createString("unsaveable_item"), ops.createString(String.valueOf(entry.item())),
                            ops.createString("count"), ops.createLong(entry.count()),
                            ops.createString("error"), ops.createString(error)));
                }
            });

    /** Who made the wafer and who last used it, for admins. */
    public record History(String createdBy, String lastUsedBy, long lastUsedAt) {
        static final History NONE = new History("", "", 0);

        static final Codec<History> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.optionalFieldOf("created_by", "").forGetter(History::createdBy),
                        Codec.STRING.optionalFieldOf("last_used_by", "").forGetter(History::lastUsedBy),
                        Codec.LONG.optionalFieldOf("last_used_at", 0L).forGetter(History::lastUsedAt))
                .apply(i, History::new));
    }

    public static final Codec<WaferRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(WaferRecord::id),
                    Codec.LONG.fieldOf("serial").forGetter(WaferRecord::serial),
                    Codec.INT.fieldOf("capacity").forGetter(WaferRecord::capacity),
                    StorageCodecs.STAMP.fieldOf("stamp").forGetter(WaferRecord::confirmed),
                    StorageCodecs.STAMP.optionalFieldOf("recovery_floor").forGetter(r -> Optional.ofNullable(r.recoveryFloor)),
                    UUIDUtil.CODEC.optionalFieldOf("archive").forGetter(r -> Optional.ofNullable(r.archiveId)),
                    Codec.STRING.optionalFieldOf("last_known_name", "").forGetter(WaferRecord::lastKnownName),
                    History.CODEC.optionalFieldOf("history", History.NONE).forGetter(WaferRecord::history),
                    CONTENTS_CODEC.fieldOf("contents").forGetter(WaferRecord::encodeContents))
            .apply(i, WaferRecord::decode));

    private final UUID id;
    private final long serial;
    private int capacity;
    private Stamp current;
    private Stamp confirmed;
    private boolean newestSeen;
    private @Nullable Stamp recoveryFloor;
    private @Nullable UUID archiveId;
    private String lastKnownName = "";
    private History history = History.NONE;
    private final WaferContents<ItemResource> contents = new WaferContents<>();
    private final List<Dynamic<?>> quarantined = new ArrayList<>();
    private long quarantinedCount;
    /** Players who changed this record since it was last written. */
    private final Set<UUID> changedBy = new HashSet<>();
    private boolean dirty;

    /** A brand-new record: nothing about it is on disk yet. */
    WaferRecord(UUID id, long serial, int capacity, Stamp stamp, Stamp confirmed) {
        this.id = id;
        this.serial = serial;
        this.capacity = capacity;
        this.current = stamp;
        this.confirmed = confirmed;
    }

    private static WaferRecord decode(UUID id, long serial, int capacity, Stamp stamp, Optional<Stamp> floor, Optional<UUID> archive,
            String name, History history, LenientListCodec.Lenient<Entry> stored) {
        WaferRecord record = new WaferRecord(id, serial, capacity, stamp, stamp);
        record.recoveryFloor = floor.orElse(null);
        record.archiveId = archive.orElse(null);
        record.lastKnownName = name;
        record.history = history;
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

    public long serial() {
        return serial;
    }

    public int capacity() {
        return capacity;
    }

    /** The newest stamp handed out. */
    public Stamp current() {
        return current;
    }

    /** The newest stamp known to be saved inside a player file; the only stamp that is written to disk. */
    public Stamp confirmed() {
        return confirmed;
    }

    public boolean newestSeen() {
        return newestSeen;
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

    public History history() {
        return history;
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

    public boolean isDirty() {
        return dirty;
    }

    public Set<UUID> changedBy() {
        return Collections.unmodifiableSet(changedBy);
    }

    public StampPolicy.RecordView view() {
        return new StampPolicy.RecordView(current, recoveryFloor, capacity, newestSeen);
    }

    // --- package-private mutators, called only by WaferStore ---

    WaferContents<ItemResource> mutableContents() {
        return contents;
    }

    /** Capacity available to decodable contents (quarantined entries keep their share). */
    long contentCapacity() {
        return Math.max(0, capacity - quarantinedCount);
    }

    void setCurrent(Stamp stamp) {
        this.current = stamp;
        this.newestSeen = true;
    }

    void markNewestSeen() {
        this.newestSeen = true;
    }

    /** A player file holding this wafer with {@code onDisk} was just saved. */
    void confirm(Stamp onDisk) {
        Stamp capped = onDisk.isAfter(current) ? current : onDisk;
        if (capped.equals(current)) {
            newestSeen = true;
        }
        if (capped.isAfter(confirmed)) {
            confirmed = capped;
            dirty = true;
        }
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

    void setHistory(History history) {
        this.history = history;
    }

    /** Records a change. A null actor (no player involved) is written at the next full save only. */
    void changed(@Nullable UUID actor) {
        dirty = true;
        if (actor != null) {
            changedBy.add(actor);
        }
    }

    void written() {
        dirty = false;
        changedBy.clear();
    }
}
