package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampAuthority;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The small world-wide part of JASM's data: the stamp epoch and counter, the next free wafer serial and the
 * Archive records. Wafer records live in {@link WaferStore}. Saved at server start and with every full save.
 */
public final class JasmState extends SavedData {
    private static final LenientListCodec<ArchiveRecord> ARCHIVES_CODEC = new LenientListCodec<>(ArchiveRecord.CODEC, "archive record");

    public static final Codec<JasmState> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.LONG.fieldOf("epoch").forGetter(s -> s.stamps.epoch()),
                    Codec.LONG.fieldOf("counter").forGetter(s -> s.stamps.counter()),
                    Codec.LONG.fieldOf("next_serial").forGetter(s -> s.nextSerial),
                    ARCHIVES_CODEC.fieldOf("archives").forGetter(s -> new LenientListCodec.Lenient<>(s.allArchivesForSave(), s.rawArchives)))
            .apply(i, JasmState::new));

    public static final SavedDataType<JasmState> TYPE = new SavedDataType<>(Jasm.id("state"), JasmState::new, CODEC);

    private final StampAuthority stamps;
    private long nextSerial;
    private final Map<UUID, ArchiveRecord> archives = new LinkedHashMap<>();
    private final List<ArchiveRecord> shadowedArchives = new ArrayList<>();
    private final List<Dynamic<?>> rawArchives;

    public JasmState() {
        this.stamps = new StampAuthority(0, 0);
        this.nextSerial = 1;
        this.rawArchives = new ArrayList<>();
    }

    private JasmState(long epoch, long counter, long nextSerial, LenientListCodec.Lenient<ArchiveRecord> archives) {
        this.stamps = new StampAuthority(epoch, counter);
        this.nextSerial = Math.max(1, nextSerial);
        for (ArchiveRecord a : archives.values()) {
            if (this.archives.putIfAbsent(a.id(), a) != null) {
                shadowedArchives.add(a);
                Jasm.LOGGER.error("Duplicate archive record {}; keeping the first, retaining the duplicate unchanged", a.id());
            }
        }
        this.rawArchives = new ArrayList<>(archives.raw());
    }

    // --- stamps ---

    public long epoch() {
        return stamps.epoch();
    }

    public void beginEpoch() {
        stamps.beginEpoch(System.currentTimeMillis());
        setDirty();
    }

    Stamp mint() {
        setDirty();
        return stamps.mint();
    }

    void observe(Stamp stamp) {
        stamps.observe(stamp);
        setDirty();
    }

    // --- serials ---

    public long nextSerial() {
        return nextSerial;
    }

    /** Hands out the next serial. Serials only go up; the caller checks the slot is really free. */
    long takeSerial() {
        setDirty();
        return nextSerial++;
    }

    /** Moves the serial counter back, as if its last save was lost in a crash. For tests only. */
    void rewindSerial(long serial) {
        nextSerial = serial;
    }

    // --- archives ---

    public ArchiveRecord createArchive(ArchiveTier tier, UUID owner, String ownerName) {
        ArchiveRecord record = new ArchiveRecord(UUID.randomUUID(), tier, owner, ownerName);
        archives.put(record.id(), record);
        setDirty();
        return record;
    }

    /** Recreates a lost record under its old id, so wafers linked to it still point at it. */
    public ArchiveRecord restoreArchive(UUID id, ArchiveTier tier, UUID owner, String ownerName) {
        ArchiveRecord record = new ArchiveRecord(id, tier, owner, ownerName);
        archives.put(id, record);
        setDirty();
        return record;
    }

    public Optional<ArchiveRecord> archive(UUID id) {
        return Optional.ofNullable(archives.get(id));
    }

    public int archiveCount() {
        return archives.size();
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

    public void addLinked(ArchiveRecord record, long serial) {
        if (record.addLinked(serial)) {
            setDirty();
        }
    }

    public void removeLinked(ArchiveRecord record, long serial) {
        if (record.removeLinked(serial)) {
            setDirty();
        }
    }

    /**
     * Writes this file now instead of at the next full save. Used after link and access changes, so that the
     * Archive's list is never older than the wafer records, which are written behind player saves.
     */
    public void saveNow(MinecraftServer server) {
        if (isDirty()) {
            server.getDataStorage().saveAndJoin();
        }
    }

    public int unreadableArchiveCount() {
        return rawArchives.size() + shadowedArchives.size();
    }

    private List<ArchiveRecord> allArchivesForSave() {
        List<ArchiveRecord> all = new ArrayList<>(archives.values());
        all.addAll(shadowedArchives);
        return all;
    }
}
