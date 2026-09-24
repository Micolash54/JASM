package dev.micolash.jasm.ledger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.core.OnceCheck;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampAuthority;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The single authority for wafer contents, stamps, links and Archives. Every mutation goes
 * through this class, runs on the server thread, and marks the data dirty.
 */
public final class JasmLedger extends SavedData {
    /** Notified after a wafer's count for one variant changes. */
    public interface WaferChangeListener {
        void onWaferChanged(UUID waferId, ItemResource key, long newCount);
    }

    private static final LenientListCodec<WaferRecord> WAFERS_CODEC = new LenientListCodec<>(WaferRecord.CODEC, "wafer record");
    private static final LenientListCodec<ArchiveRecord> ARCHIVES_CODEC = new LenientListCodec<>(ArchiveRecord.CODEC, "archive record");

    public static final Codec<JasmLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.LONG.fieldOf("epoch").forGetter(l -> l.stamps.epoch()),
                    Codec.LONG.fieldOf("counter").forGetter(l -> l.stamps.counter()),
                    WAFERS_CODEC.fieldOf("wafers").forGetter(l -> new LenientListCodec.Lenient<>(l.allWafersForSave(), l.rawWafers)),
                    ARCHIVES_CODEC.fieldOf("archives").forGetter(l -> new LenientListCodec.Lenient<>(l.allArchivesForSave(), l.rawArchives)))
            .apply(i, JasmLedger::new));

    public static final SavedDataType<JasmLedger> TYPE = new SavedDataType<>(Jasm.id("ledger"), JasmLedger::new, CODEC);

    private static final OnceCheck<MinecraftServer> LOAD_GUARD = new OnceCheck<>(LedgerFiles::guardLoad);

    private final StampAuthority stamps;
    private final Map<UUID, WaferRecord> wafers = new LinkedHashMap<>();
    private final Map<UUID, ArchiveRecord> archives = new LinkedHashMap<>();
    private final List<Dynamic<?>> rawWafers;
    private final List<Dynamic<?>> rawArchives;
    private final List<WaferRecord> shadowedWafers = new ArrayList<>();
    private final List<ArchiveRecord> shadowedArchives = new ArrayList<>();
    private final List<WaferChangeListener> listeners = new CopyOnWriteArrayList<>();

    public JasmLedger() {
        this.stamps = new StampAuthority(0, 0);
        this.rawWafers = new ArrayList<>();
        this.rawArchives = new ArrayList<>();
    }

    private JasmLedger(long epoch, long counter, LenientListCodec.Lenient<WaferRecord> wafers, LenientListCodec.Lenient<ArchiveRecord> archives) {
        this.stamps = new StampAuthority(epoch, counter);
        for (WaferRecord w : wafers.values()) {
            if (this.wafers.putIfAbsent(w.id(), w) != null) {
                shadowedWafers.add(w);
                Jasm.LOGGER.error("Duplicate wafer record {} in ledger; keeping the first, retaining the duplicate unchanged", w.id());
            }
        }
        for (ArchiveRecord a : archives.values()) {
            if (this.archives.putIfAbsent(a.id(), a) != null) {
                shadowedArchives.add(a);
                Jasm.LOGGER.error("Duplicate archive record {} in ledger; keeping the first, retaining the duplicate unchanged", a.id());
            }
        }
        this.rawWafers = new ArrayList<>(wafers.raw());
        this.rawArchives = new ArrayList<>(archives.raw());
    }

    /** The server's ledger. The first call per server verifies an existing file actually loaded; a failed
     *  check is rethrown on every later call, so an empty ledger can never replace the real file. */
    public static JasmLedger get(MinecraftServer server) {
        LOAD_GUARD.ensure(server);
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    // --- stamps ---

    public long epoch() {
        return stamps.epoch();
    }

    public void beginEpoch() {
        stamps.beginEpoch();
        setDirty();
    }

    /** Gives the record a fresh stamp; the caller must write it into the physical item. */
    public Stamp rotate(WaferRecord record) {
        Stamp stamp = stamps.mint();
        record.setCurrent(stamp);
        setDirty();
        return stamp;
    }

    /** Accepts an item stamp that is ahead of the ledger (the item was saved, the ledger was not). */
    public void fastForward(WaferRecord record, Stamp itemStamp) {
        record.setCurrent(itemStamp);
        stamps.observe(itemStamp);
        setDirty();
    }

    /** Recovery/restore: every older instance becomes a recovered original. Returns the new instance stamp. */
    public Stamp reissue(WaferRecord record, int newCapacity) {
        Stamp stamp = stamps.mint();
        record.setCurrent(stamp);
        record.setRecoveryFloor(stamp);
        record.setCapacity(newCapacity);
        setDirty();
        return stamp;
    }

    // --- wafers ---

    public WaferRecord createWafer(int capacity) {
        WaferRecord record = new WaferRecord(UUID.randomUUID(), capacity, stamps.mint());
        wafers.put(record.id(), record);
        setDirty();
        return record;
    }

    public Optional<WaferRecord> wafer(UUID id) {
        return Optional.ofNullable(wafers.get(id));
    }

    public Collection<WaferRecord> wafers() {
        return Collections.unmodifiableCollection(wafers.values());
    }

    /** Only empty, unlinked records may be deleted (a blank consumed by recovery). */
    public void deleteEmptyWafer(WaferRecord record) {
        if (!record.isEmpty() || record.archiveId() != null) {
            throw new IllegalStateException("Refusing to delete non-empty or linked wafer " + record.id());
        }
        wafers.remove(record.id());
        setDirty();
    }

    /** Stores up to {@code amount}; returns the accepted amount. */
    public long insert(WaferRecord record, ItemResource key, long amount, boolean simulate) {
        long accepted = record.mutableContents().insert(key, amount, record.contentCapacity(), simulate);
        if (!simulate && accepted > 0) {
            changed(record, key);
        }
        return accepted;
    }

    /** Removes up to {@code amount}; returns the removed amount. */
    public long extract(WaferRecord record, ItemResource key, long amount, boolean simulate) {
        long taken = record.mutableContents().extract(key, amount, simulate);
        if (!simulate && taken > 0) {
            changed(record, key);
        }
        return taken;
    }

    public void setLink(WaferRecord record, @Nullable UUID archiveId, String displayName) {
        record.setArchiveId(archiveId);
        record.setLastKnownName(displayName);
        setDirty();
    }

    public List<WaferRecord> linkedTo(UUID archiveId) {
        return wafers.values().stream().filter(w -> archiveId.equals(w.archiveId())).toList();
    }

    public void addListener(WaferChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(WaferChangeListener listener) {
        listeners.remove(listener);
    }

    private void changed(WaferRecord record, ItemResource key) {
        setDirty();
        long now = record.count(key);
        for (WaferChangeListener listener : listeners) {
            listener.onWaferChanged(record.id(), key, now);
        }
    }

    // --- archives ---

    public ArchiveRecord createArchive(ArchiveTier tier, UUID owner, String ownerName) {
        ArchiveRecord record = new ArchiveRecord(UUID.randomUUID(), tier, owner, ownerName);
        archives.put(record.id(), record);
        setDirty();
        return record;
    }

    public Optional<ArchiveRecord> archive(UUID id) {
        return Optional.ofNullable(archives.get(id));
    }

    public void setPlacement(ArchiveRecord record, ArchiveRecord.@Nullable Placement placement) {
        record.setPlacement(placement);
        setDirty();
    }

    public void trust(ArchiveRecord record, UUID player, String name) {
        record.putTrusted(player, name);
        setDirty();
    }

    public void untrust(ArchiveRecord record, UUID player) {
        record.removeTrusted(player);
        setDirty();
    }

    // --- stats ---

    public int archiveCount() {
        return archives.size();
    }

    public int undecodableRecordCount() {
        return rawWafers.size() + rawArchives.size() + shadowedWafers.size() + shadowedArchives.size();
    }

    private List<WaferRecord> allWafersForSave() {
        List<WaferRecord> all = new ArrayList<>(wafers.values());
        all.addAll(shadowedWafers);
        return all;
    }

    private List<ArchiveRecord> allArchivesForSave() {
        List<ArchiveRecord> all = new ArrayList<>(archives.values());
        all.addAll(shadowedArchives);
        return all;
    }
}
