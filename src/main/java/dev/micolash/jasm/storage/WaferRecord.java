package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.WaferContents;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferKind;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
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

    /** One stored fluid variant and its amount in millibuckets. */
    public record FluidEntry(FluidResource fluid, long amount) {
        public static final Codec<FluidEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                FluidResource.CODEC.fieldOf("fluid").forGetter(FluidEntry::fluid),
                Codec.LONG.fieldOf("amount").forGetter(FluidEntry::amount))
                .apply(i, FluidEntry::new));
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

    /** Same for a fluid: it keeps its name and amount, so it still takes up space and shows up for admins. */
    private static final LenientListCodec<FluidEntry> FLUIDS_CODEC = new LenientListCodec<>(FluidEntry.CODEC, "wafer fluid",
            new LenientListCodec.Placeholder<>() {
                @Override
                public <T> T encode(FluidEntry entry, String error, DynamicOps<T> ops) {
                    return ops.createMap(Map.of(
                            ops.createString("unsaveable_fluid"), ops.createString(String.valueOf(entry.fluid())),
                            ops.createString("amount"), ops.createLong(entry.amount()),
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

    /**
     * Everything that is written to disk, copied on the server thread. Nothing in it changes afterwards, so it can
     * be encoded on another thread while the record keeps changing.
     */
    public record Snapshot(UUID id, long serial, long capacity, int types, int perType, Stamp stamp, Optional<Stamp> recoveryFloor,
            Optional<UUID> archive, String lastKnownName, History history, WaferSettings settings, LenientListCodec.Lenient<Entry> contents,
            WaferKind kind, LenientListCodec.Lenient<FluidEntry> fluids) {
        public static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Snapshot::id),
                Codec.LONG.fieldOf("serial").forGetter(Snapshot::serial),
                // Saved as an int before the biggest wafers existed; the long codec reads both.
                Codec.LONG.fieldOf("capacity").forGetter(Snapshot::capacity),
                Codec.INT.optionalFieldOf("types", 0).forGetter(Snapshot::types),
                Codec.INT.optionalFieldOf("per_type", 0).forGetter(Snapshot::perType),
                StorageCodecs.STAMP.fieldOf("stamp").forGetter(Snapshot::stamp),
                StorageCodecs.STAMP.optionalFieldOf("recovery_floor").forGetter(Snapshot::recoveryFloor),
                UUIDUtil.CODEC.optionalFieldOf("archive").forGetter(Snapshot::archive),
                Codec.STRING.optionalFieldOf("last_known_name", "").forGetter(Snapshot::lastKnownName),
                History.CODEC.optionalFieldOf("history", History.NONE).forGetter(Snapshot::history),
                WaferSettings.CODEC.optionalFieldOf("settings", WaferSettings.DEFAULT).forGetter(Snapshot::settings),
                CONTENTS_CODEC.fieldOf("contents").forGetter(Snapshot::contents),
                WaferKind.CODEC.optionalFieldOf("kind", WaferKind.ITEM).forGetter(Snapshot::kind),
                FLUIDS_CODEC.optionalFieldOf("fluids", LenientListCodec.Lenient.of(List.of())).forGetter(Snapshot::fluids))
                .apply(i, Snapshot::new));
    }

    public static final Codec<WaferRecord> CODEC = Snapshot.CODEC.xmap(WaferRecord::decode, WaferRecord::snapshot);

    private final UUID id;
    private final long serial;
    private long capacity;
    /** Type Wafers only: most types, and most items of one type. 0 on Capacity Wafers. */
    private int types;
    private int perType;
    private Stamp current;
    private Stamp confirmed;
    private boolean newestSeen;
    private @Nullable Stamp recoveryFloor;
    private @Nullable UUID archiveId;
    private String lastKnownName = "";
    private History history = History.NONE;
    private WaferSettings settings = WaferSettings.DEFAULT;
    private WaferKind kind = WaferKind.ITEM;
    private final WaferContents<ItemResource> contents = new WaferContents<>();
    private final List<Dynamic<?>> quarantined = new ArrayList<>();
    private long quarantinedCount;
    /** A fluid wafer keeps its fluids here, in millibuckets; an item wafer leaves these empty. */
    private final WaferContents<FluidResource> fluids = new WaferContents<>();
    private final List<Dynamic<?>> quarantinedFluid = new ArrayList<>();
    private long quarantinedFluidCount;
    /** Players who changed this record since it was last written. */
    private final Set<UUID> changedBy = new HashSet<>();
    private boolean dirty;
    /** The server tick this record was last looked up or changed on; not saved. */
    private long touched;

    /** A brand-new record: nothing about it is on disk yet. */
    WaferRecord(UUID id, long serial, long capacity, Stamp stamp, Stamp confirmed) {
        this.id = id;
        this.serial = serial;
        this.capacity = capacity;
        this.current = stamp;
        this.confirmed = confirmed;
    }

    private static WaferRecord decode(Snapshot saved) {
        WaferRecord record = new WaferRecord(saved.id(), saved.serial(), saved.capacity(), saved.stamp(), saved.stamp());
        record.types = saved.types();
        record.perType = saved.perType();
        record.recoveryFloor = saved.recoveryFloor().orElse(null);
        record.archiveId = saved.archive().orElse(null);
        record.lastKnownName = saved.lastKnownName();
        record.history = saved.history();
        record.settings = saved.settings();
        record.kind = saved.kind();
        LenientListCodec.Lenient<Entry> stored = saved.contents();
        for (Entry entry : stored.values()) {
            if (!entry.item().isEmpty() && entry.count() > 0) {
                record.contents.putLoaded(entry.item(), entry.count());
            }
        }
        for (Dynamic<?> raw : stored.raw()) {
            record.quarantined.add(raw);
            record.quarantinedCount += Math.max(0, raw.get("count").asLong(0));
        }
        LenientListCodec.Lenient<FluidEntry> storedFluids = saved.fluids();
        for (FluidEntry entry : storedFluids.values()) {
            if (!entry.fluid().isEmpty() && entry.amount() > 0) {
                record.fluids.putLoaded(entry.fluid(), entry.amount());
            }
        }
        for (Dynamic<?> raw : storedFluids.raw()) {
            record.quarantinedFluid.add(raw);
            record.quarantinedFluidCount += Math.max(0, raw.get("amount").asLong(0));
        }
        return record;
    }

    /** What would be written right now, with {@code confirmed} as the stamp. */
    public Snapshot snapshot() {
        List<Entry> entries = new ArrayList<>(contents.view().size());
        for (Map.Entry<ItemResource, Long> e : contents.view().entrySet()) {
            entries.add(new Entry(e.getKey(), e.getValue()));
        }
        List<FluidEntry> fluidEntries = new ArrayList<>(fluids.view().size());
        for (Map.Entry<FluidResource, Long> e : fluids.view().entrySet()) {
            fluidEntries.add(new FluidEntry(e.getKey(), e.getValue()));
        }
        return new Snapshot(id, serial, capacity, types, perType, confirmed, Optional.ofNullable(recoveryFloor), Optional.ofNullable(archiveId),
                lastKnownName, history, settings, new LenientListCodec.Lenient<>(List.copyOf(entries), List.copyOf(quarantined)),
                kind, new LenientListCodec.Lenient<>(List.copyOf(fluidEntries), List.copyOf(quarantinedFluid)));
    }

    public UUID id() {
        return id;
    }

    public long serial() {
        return serial;
    }

    public long capacity() {
        return capacity;
    }

    /** Most types this wafer takes, or 0 for any number (Capacity Wafers). */
    public int types() {
        return types;
    }

    public int perType() {
        return perType;
    }

    public boolean isTyped() {
        return types > 0;
    }

    public WaferKind kind() {
        return kind;
    }

    public boolean isFluid() {
        return kind == WaferKind.FLUID;
    }

    /** Types in use; always 0 on Capacity Wafers, which don't count them. */
    public long typesUsed() {
        if (!isTyped()) {
            return 0;
        }
        return isFluid() ? TypeRules.fluidTypesUsed(fluids.view().size(), quarantinedFluid.size())
                : TypeRules.typesUsed(contents.view(), quarantined.size());
    }

    /** How many of {@code item} still fit: free space, and on Type Wafers the type limits too. A fluid wafer takes none. */
    public long roomFor(ItemResource item) {
        if (isFluid()) {
            return 0;
        }
        long free = Math.max(0, contentCapacity() - contents.total());
        return isTyped() ? Math.min(free, TypeRules.room(item, contents.count(item), typesUsed(), types, perType)) : free;
    }

    /** How many millibuckets of {@code fluid} still fit. An item wafer takes none. */
    public long roomForFluid(FluidResource fluid) {
        if (!isFluid()) {
            return 0;
        }
        long free = Math.max(0, contentCapacityFluid() - fluids.total());
        return isTyped() ? Math.min(free, TypeRules.fluidRoom(fluids.count(fluid), typesUsed(), types, FluidAmounts.roomMb(perType))) : free;
    }

    /** Total room in the wafer's own unit: items, or millibuckets on a fluid wafer. */
    public long capacityAmount() {
        return isFluid() ? FluidAmounts.roomMb(capacity) : capacity;
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

    /** Priority and filter for Deck routing. */
    public WaferSettings settings() {
        return settings;
    }

    /** Read-only view of the fluids a fluid wafer holds. */
    public Map<FluidResource, Long> fluids() {
        return fluids.view();
    }

    public long countFluid(FluidResource key) {
        return fluids.count(key);
    }

    /** Read-only view of decodable contents. */
    public Map<ItemResource, Long> contents() {
        return contents.view();
    }

    public long count(ItemResource key) {
        return contents.count(key);
    }

    /** Amount from removed mods that is kept but can't be read, in the wafer's own unit. */
    public long quarantinedCount() {
        return isFluid() ? quarantinedFluidCount : quarantinedCount;
    }

    /** What counts against capacity, including unreadable entries: items, or millibuckets on a fluid wafer. */
    public long used() {
        return isFluid() ? fluids.total() + quarantinedFluidCount : contents.total() + quarantinedCount;
    }

    public long free() {
        return Math.max(0, capacityAmount() - used());
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

    WaferContents<FluidResource> mutableFluids() {
        return fluids;
    }

    /** Room for readable fluids, in millibuckets (unreadable entries keep their share). */
    long contentCapacityFluid() {
        return Math.max(0, capacityAmount() - quarantinedFluidCount);
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

    void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    /** A job's hidden record for fluids: a fluid record with no limits of its own. */
    void makeFluid() {
        this.kind = WaferKind.FLUID;
    }

    void setLimits(WaferTier tier) {
        this.kind = tier.kind();
        this.capacity = tier.capacity();
        this.types = tier.types();
        this.perType = tier.perType();
    }

    void setArchiveId(@Nullable UUID archiveId) {
        this.archiveId = archiveId;
    }

    void setLastKnownName(String name) {
        this.lastKnownName = name;
    }

    void setSettings(WaferSettings settings) {
        this.settings = settings;
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

    /** Hands this wafer's entries from removed mods to {@code target}. */
    void moveQuarantinedTo(WaferRecord target) {
        target.quarantined.addAll(quarantined);
        target.quarantinedCount += quarantinedCount;
        quarantined.clear();
        quarantinedCount = 0;
        target.quarantinedFluid.addAll(quarantinedFluid);
        target.quarantinedFluidCount += quarantinedFluidCount;
        quarantinedFluid.clear();
        quarantinedFluidCount = 0;
    }

    void written() {
        dirty = false;
        changedBy.clear();
    }

    long touched() {
        return touched;
    }

    void touch(long tick) {
        touched = tick;
    }
}
