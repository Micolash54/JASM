package dev.micolash.jasm.storage;

import com.mojang.serialization.DataResult;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.OnceCheck;
import dev.micolash.jasm.core.SerialLayout;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.WaferHolderItem;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Every wafer's record, one per slot in region files under {@code data/jasm/wafers}. Records load on first use
 * and stay cached. A record is only written after the player files it has to agree with (see {@link StorageEvents}):
 * after every player who changed it has been saved, and with the newest stamp known to be inside a saved player file.
 * All methods run on the server thread. Writing copies the record there and turns the copy into NBT in the
 * background, so a save never waits on big wafers.
 */
public final class WaferStore {
    /** Notified after a wafer's count for one variant changes. */
    public interface WaferChangeListener {
        void onWaferChanged(UUID waferId, ItemResource key, long newCount);
    }

    /** The result of looking up a wafer's record. */
    public enum Status {
        FOUND,
        /** No record in that slot, or the slot holds a different wafer. */
        MISSING,
        /** Something is stored there but cannot be read right now. */
        UNREADABLE
    }

    public record Lookup(Status status, @Nullable WaferRecord record) {
        static final Lookup MISSING = new Lookup(Status.MISSING, null);
        static final Lookup UNREADABLE = new Lookup(Status.UNREADABLE, null);
    }

    private static final Stamp NOTHING_SAVED = new Stamp(0, 0);
    private static final OnceCheck<MinecraftServer> STATE_GUARD = new OnceCheck<>(StateFiles::guardLoad);
    private static @Nullable WaferStore open;

    private final MinecraftServer server;
    private final JasmState state;
    private final SimpleRegionStorage storage;
    private final Map<Long, WaferRecord> records = new HashMap<>();
    private final Set<Long> emptySlots = new HashSet<>();
    private final Set<Long> unreadableSlots = new HashSet<>();
    private final List<WaferChangeListener> listeners = new CopyOnWriteArrayList<>();
    /** The newest write of each record that is still on its way to disk. A later write of the same record waits for it. */
    private final Map<Long, CompletableFuture<Void>> pendingWrites = new HashMap<>();
    /** Set while this store saves other players' files, so their save events do not start another round. */
    private boolean savingPlayers;

    private WaferStore(MinecraftServer server, JasmState state) {
        this.server = server;
        this.state = state;
        this.storage = new SimpleRegionStorage(
                new RegionStorageInfo(server.getWorldData().getLevelName(), Level.OVERWORLD, "jasm_wafers"),
                StateFiles.waferFolder(server), server.getFixerUpper(), false, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    }

    /**
     * The store of a running server. The first call checks that the state file loaded; a failed check is rethrown
     * on every later call, so an empty state can never replace the real one.
     */
    public static WaferStore get(MinecraftServer server) {
        WaferStore store = open;
        if (store != null && store.server == server) {
            return store;
        }
        STATE_GUARD.ensure(server);
        store = new WaferStore(server, server.getDataStorage().computeIfAbsent(JasmState.TYPE));
        open = store;
        return store;
    }

    /** The store if it was opened for this server (it is at server start, unless the state check failed). */
    public static @Nullable WaferStore ifOpen(MinecraftServer server) {
        WaferStore store = open;
        return store != null && store.server == server ? store : null;
    }

    /** Finishes pending writes and closes the region files. */
    static void close(MinecraftServer server) {
        WaferStore store = open;
        if (store == null || store.server != server) {
            return;
        }
        open = null;
        try {
            store.awaitWrites();
            store.storage.close();
        } catch (IOException e) {
            Jasm.LOGGER.error("Could not close JASM wafer storage", e);
        }
    }

    public JasmState state() {
        return state;
    }

    // --- lookup ---

    public Lookup find(WaferIdentity identity) {
        if (identity.serial() < 1) {
            return Lookup.MISSING;
        }
        Lookup slot = load(identity.serial());
        if (slot.record() != null && !slot.record().id().equals(identity.id())) {
            return Lookup.MISSING;
        }
        return slot;
    }

    public Optional<WaferRecord> bySerial(long serial) {
        return serial < 1 ? Optional.empty() : Optional.ofNullable(load(serial).record());
    }

    private Lookup load(long serial) {
        WaferRecord cached = records.get(serial);
        if (cached != null) {
            return new Lookup(Status.FOUND, cached);
        }
        if (unreadableSlots.contains(serial)) {
            return Lookup.UNREADABLE;
        }
        if (emptySlots.contains(serial)) {
            return Lookup.MISSING;
        }
        Optional<CompoundTag> tag;
        try {
            tag = storage.read(pos(serial)).join();
        } catch (RuntimeException e) {
            Jasm.LOGGER.error("Could not read wafer record #{}; the wafer is locked until it can be read", serial, e);
            unreadableSlots.add(serial);
            return Lookup.UNREADABLE;
        }
        if (tag.isEmpty()) {
            emptySlots.add(serial);
            return Lookup.MISSING;
        }
        DataResult<WaferRecord> parsed = WaferRecord.CODEC.parse(ops(), tag.get().get("record"));
        Optional<WaferRecord> record = parsed.result();
        if (record.isEmpty() || record.get().serial() != serial) {
            Jasm.LOGGER.error("Wafer record #{} cannot be read ({}); it is kept unchanged and the wafer is locked", serial,
                    parsed.error().map(e -> e.message()).orElse("serial mismatch"));
            unreadableSlots.add(serial);
            return Lookup.UNREADABLE;
        }
        records.put(serial, record.get());
        relinkIfMissing(record.get());
        return new Lookup(Status.FOUND, record.get());
    }

    /**
     * The wafer's own record decides which Archive it is linked to. If that Archive's list lost it (its record was
     * rebuilt, or an older state file was put back), it goes back on the list.
     */
    private void relinkIfMissing(WaferRecord record) {
        UUID archiveId = record.archiveId();
        ArchiveRecord archive = archiveId == null ? null : state.archive(archiveId).orElse(null);
        if (archive != null && archive.discarded(record.serial())) {
            setLink(record, null, record.lastKnownName(), null);
            return;
        }
        if (archive != null && !archive.linked().contains(record.serial())) {
            Jasm.LOGGER.info("Wafer #{} is linked to Archive {}, which had lost it from its list; adding it back", record.serial(), archiveId);
            state.addLinked(archive, record.serial());
        }
    }

    /**
     * Rebuilds the list of an Archive whose record was lost: every wafer whose record points at it goes back on
     * the list. The region files are read in the background; the list is filled in and saved on the server thread.
     */
    public CompletableFuture<Integer> relinkAll(ArchiveRecord archive) {
        UUID archiveId = archive.id();
        long limit = state.nextSerial();
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        Set<Long> onDisk = ConcurrentHashMap.newKeySet();
        for (long serial = 1; serial < limit; serial++) {
            if (records.containsKey(serial) || emptySlots.contains(serial) || unreadableSlots.contains(serial)) {
                continue;
            }
            long slot = serial;
            reads.add(storage.read(pos(serial)).thenAccept(tag -> {
                if (tag.isPresent() && archiveId.equals(savedArchive(tag.get()))) {
                    onDisk.add(slot);
                }
            }).exceptionally(failure -> null));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApplyAsync(ignored -> {
            int found = 0;
            for (long serial = 1; serial < limit; serial++) {
                WaferRecord record = records.get(serial);
                if (record == null && onDisk.contains(serial)) {
                    record = load(serial).record();
                }
                if (record != null && archiveId.equals(record.archiveId()) && !archive.discarded(serial)) {
                    state.addLinked(archive, serial);
                    found++;
                }
            }
            state.saveNow(server);
            Jasm.LOGGER.info("Archive {} got its list back: {} linked wafers found", archiveId, found);
            return found;
        }, server);
    }

    /** The Archive a saved slot is linked to, read straight from the NBT without decoding the record. */
    private static @Nullable UUID savedArchive(CompoundTag slot) {
        Tag archive = slot.getCompound("record").map(record -> record.get("archive")).orElse(null);
        return archive == null ? null : UUIDUtil.CODEC.parse(NbtOps.INSTANCE, archive).result().orElse(null);
    }

    /** Serials only go up. A slot that holds anything, or cannot be read, is skipped and never overwritten. */
    private long allocateSerial() {
        while (true) {
            long serial = state.takeSerial();
            if (load(serial).status() == Status.MISSING && !records.containsKey(serial)) {
                emptySlots.remove(serial);
                return serial;
            }
            Jasm.LOGGER.warn("Wafer serial #{} is already in use on disk; skipping it", serial);
        }
    }

    // --- stamps ---

    /** Creates the record for a freshly formatted wafer of this tier. */
    public WaferRecord create(WaferTier tier, @Nullable Player actor) {
        WaferRecord record = create(tier.capacity(), actor);
        record.setLimits(tier);
        return record;
    }

    /** Creates the record for a freshly formatted Capacity Wafer. */
    public WaferRecord create(int capacity, @Nullable Player actor) {
        long serial = allocateSerial();
        WaferRecord record = new WaferRecord(UUID.randomUUID(), serial, capacity, state.mint(), NOTHING_SAVED);
        String name = actor == null ? "" : actor.getPlainTextName();
        record.setHistory(new WaferRecord.History(name, name, System.currentTimeMillis()));
        record.changed(uuid(actor));
        records.put(serial, record);
        return record;
    }

    /** Gives the record a fresh stamp; the caller must write it into the physical item. */
    public Stamp rotate(WaferRecord record, @Nullable Player holder) {
        Stamp stamp = state.mint();
        record.setCurrent(stamp);
        used(record, holder);
        return stamp;
    }

    /** Accepts an item stamp that is ahead of the record (the item was saved, the record was not). */
    public void fastForward(WaferRecord record, Stamp itemStamp) {
        if (!itemStamp.isAfter(record.current())) {
            throw new IllegalArgumentException("Stamp " + itemStamp + " is not ahead of wafer #" + record.serial() + " at " + record.current());
        }
        record.setCurrent(itemStamp);
        state.observe(itemStamp);
    }

    /** An item carrying the newest stamp was just seen. */
    public void markSeen(WaferRecord record) {
        record.markNewestSeen();
    }

    /** Recovery onto a wafer of this tier: as {@link #reissue(WaferRecord, int, Player)}, taking the new wafer's limits. */
    public Stamp reissue(WaferRecord record, WaferTier tier, @Nullable Player actor) {
        Stamp stamp = reissue(record, tier.capacity(), actor);
        record.setLimits(tier);
        return stamp;
    }

    /** Recovery or restore: every older instance becomes a recovered original. Returns the new instance's stamp. */
    public Stamp reissue(WaferRecord record, int newCapacity, @Nullable Player actor) {
        Stamp stamp = state.mint();
        record.setCurrent(stamp);
        record.setRecoveryFloor(stamp);
        record.setCapacity(newCapacity);
        used(record, actor);
        return stamp;
    }

    // --- contents ---

    /**
     * Stores up to {@code amount}; returns the accepted amount. Type Wafers also keep to their type limits. A null
     * actor means no player was involved.
     */
    public long insert(WaferRecord record, ItemResource key, long amount, boolean simulate, @Nullable Player actor) {
        long fits = record.isTyped() ? Math.min(amount, record.roomFor(key)) : amount;
        long accepted = record.mutableContents().insert(key, fits, record.contentCapacity(), simulate);
        if (!simulate && accepted > 0) {
            contentsChanged(record, key, actor);
        }
        return accepted;
    }

    /** Removes up to {@code amount}; returns the removed amount. A null actor means no player was involved. */
    public long extract(WaferRecord record, ItemResource key, long amount, boolean simulate, @Nullable Player actor) {
        long taken = record.mutableContents().extract(key, amount, simulate);
        if (!simulate && taken > 0) {
            contentsChanged(record, key, actor);
        }
        return taken;
    }

    /**
     * Moves everything {@code source} holds into {@code target}, including items from removed mods, then closes the
     * source: it is unlinked, and its record jumps past every copy of it, so none of them can use it or be recovered
     * from it again. Used when wafers are crafted into a bigger one; the caller checks everything fits.
     */
    public void absorb(WaferRecord target, WaferRecord source, @Nullable Player actor) {
        source.moveQuarantinedTo(target);
        target.changed(uuid(actor));
        for (Map.Entry<ItemResource, Long> entry : List.copyOf(source.contents().entrySet())) {
            long moved = insert(target, entry.getKey(), entry.getValue(), false, actor);
            extract(source, entry.getKey(), moved, false, actor);
            if (moved < entry.getValue()) {
                Jasm.LOGGER.error("Wafer #{} had no room for {} of {} from wafer #{}; they stay on #{}", target.serial(),
                        entry.getValue() - moved, entry.getKey(), source.serial(), source.serial());
            }
        }
        UUID archiveId = source.archiveId();
        if (archiveId != null) {
            state.archive(archiveId).ifPresent(archive -> state.removeLinked(archive, source.serial()));
            setLink(source, null, source.lastKnownName(), actor);
        }
        reissue(source, source.capacity(), actor);
    }

    /** Changes how a Deck routes items to this wafer. */
    public void setSettings(WaferRecord record, WaferSettings settings, @Nullable Player actor) {
        if (!settings.equals(record.settings())) {
            record.setSettings(settings);
            used(record, actor);
        }
    }

    public void setLink(WaferRecord record, @Nullable UUID archiveId, String displayName, @Nullable Player actor) {
        record.setArchiveId(archiveId);
        record.setLastKnownName(displayName);
        used(record, actor);
    }

    public void addListener(WaferChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(WaferChangeListener listener) {
        listeners.remove(listener);
    }

    private void contentsChanged(WaferRecord record, ItemResource key, @Nullable Player actor) {
        used(record, actor);
        long now = record.count(key);
        for (WaferChangeListener listener : listeners) {
            listener.onWaferChanged(record.id(), key, now);
        }
    }

    private void used(WaferRecord record, @Nullable Player actor) {
        record.changed(uuid(actor));
        if (actor != null) {
            WaferRecord.History h = record.history();
            record.setHistory(new WaferRecord.History(h.createdBy(), actor.getPlainTextName(), System.currentTimeMillis()));
        }
    }

    // --- saving ---

    /**
     * A player's file was just written. Every wafer in it now counts as saved with the stamp it carries. Unless
     * this is part of a full save, the records this player changed or holds are written right behind the file,
     * after first saving any other online player who also changed them.
     */
    void afterPlayerSaved(ServerPlayer saved, List<ServerPlayer> online, boolean fullSave) {
        Set<WaferRecord> held = confirmHeld(saved);
        if (fullSave || savingPlayers) {
            return;
        }
        UUID savedId = saved.getUUID();
        List<WaferRecord> due = new ArrayList<>();
        Set<UUID> coUsers = new HashSet<>();
        for (WaferRecord record : records.values()) {
            if (record.isDirty() && (record.changedBy().contains(savedId) || held.contains(record))) {
                due.add(record);
                coUsers.addAll(record.changedBy());
            }
        }
        if (due.isEmpty()) {
            return;
        }
        coUsers.remove(savedId);
        savingPlayers = true;
        try {
            for (ServerPlayer other : online) {
                if (other != saved && coUsers.contains(other.getUUID())) {
                    server.getPlayerList().getPlayerIo().save(other);
                }
            }
            List<CompletableFuture<Void>> writes = new ArrayList<>();
            for (WaferRecord record : due) {
                writes.add(write(record));
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException e) {
            Jasm.LOGGER.error("Could not write wafer records behind {}'s save", saved.getPlainTextName(), e);
        } finally {
            savingPlayers = false;
        }
    }

    /**
     * Writes a record now if it has changes, without waiting for the next save: for a job whose items went into a
     * machine whose chunk is being saved right now, so that the two agree after a crash.
     */
    public void writeNow(WaferRecord record) {
        if (record.isDirty()) {
            write(record);
        }
    }

    /** Full save: every player file has already been written. */
    void writeAllDirty() {
        for (WaferRecord record : records.values()) {
            if (record.isDirty()) {
                write(record);
            }
        }
    }

    private Set<WaferRecord> confirmHeld(ServerPlayer player) {
        Set<WaferRecord> held = new HashSet<>();
        forEachHeldWafer(player, stack -> {
            WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
            WaferRecord record = identity == null ? null : records.get(identity.serial());
            if (record != null && record.id().equals(identity.id())) {
                record.confirm(identity.stamp());
                held.add(record);
            }
        });
        return held;
    }

    /** Every wafer that is saved as part of a player's file: inventory, ender chest, and inside Decks in either. */
    public static void forEachHeldWafer(Player player, Consumer<ItemStack> action) {
        Consumer<ItemStack> visit = stack -> visitWafers(stack, action);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            visit.accept(player.getInventory().getItem(i));
        }
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            visit.accept(player.getEnderChestInventory().getItem(i));
        }
    }

    private static void visitWafers(ItemStack stack, Consumer<ItemStack> action) {
        if (stack.getItem() instanceof WaferItem) {
            action.accept(stack);
        } else if (stack.getItem() instanceof WaferHolderItem holder) {
            holder.forEachWafer(stack, action);
        }
    }

    /**
     * Copies the record now and writes the copy in the background. Writes of the same record reach the region file
     * in the order they were made. If one fails, the last saved copy stays on disk and the record is written again
     * at the next save.
     */
    private CompletableFuture<Void> write(WaferRecord record) {
        long serial = record.serial();
        WaferRecord.Snapshot snapshot = record.snapshot();
        record.written();
        RegistryOps<Tag> ops = ops();
        CompletableFuture<Void> previous = pendingWrites.getOrDefault(serial, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> next = previous
                .handle((ok, failure) -> snapshot)
                .thenApplyAsync(copy -> encode(copy, ops), Util.backgroundExecutor())
                .thenCompose(slot -> storage.write(pos(serial), slot));
        pendingWrites.put(serial, next);
        next.whenComplete((ok, failure) -> server.execute(() -> {
            pendingWrites.remove(serial, next);
            if (failure != null) {
                Jasm.LOGGER.error("Could not save wafer record #{}; the last saved copy stays on disk and it will be written again at the next save",
                        serial, failure);
                record.changed(null);
            }
        }));
        return next;
    }

    /** Runs in the background: nothing here may touch the record or the world. */
    private static CompoundTag encode(WaferRecord.Snapshot snapshot, RegistryOps<Tag> ops) {
        Tag encoded = WaferRecord.Snapshot.CODEC.encodeStart(ops, snapshot).getOrThrow(IllegalStateException::new);
        CompoundTag slot = new CompoundTag();
        slot.put("record", encoded);
        NbtUtils.addCurrentDataVersion(slot);
        return slot;
    }

    /** Blocks until every write started so far is in the region files. Failures are already logged. */
    private void awaitWrites() {
        CompletableFuture.allOf(pendingWrites.values().stream()
                .map(write -> write.handle((ok, failure) -> null))
                .toArray(CompletableFuture[]::new)).join();
    }

    // --- tests ---

    /** Puts raw data into a slot, as if written by something else. For tests only. */
    CompletableFuture<Void> writeRaw(long serial, CompoundTag slot) {
        records.remove(serial);
        emptySlots.remove(serial);
        unreadableSlots.remove(serial);
        return storage.write(pos(serial), slot);
    }

    /** Waits until every queued write has reached the region files. For tests only. */
    void flush() {
        awaitWrites();
        storage.synchronize(true).join();
    }

    /** Drops a saved record from memory so its next use reads it from disk, as after a restart. For tests only. */
    boolean unload(long serial) {
        WaferRecord record = records.get(serial);
        CompletableFuture<Void> pending = pendingWrites.get(serial);
        if (record == null || record.isDirty() || (pending != null && !pending.isDone())) {
            return false;
        }
        records.remove(serial);
        return true;
    }

    // --- stats ---

    public int loadedCount() {
        return records.size();
    }

    public int unsavedCount() {
        return (int) records.values().stream().filter(WaferRecord::isDirty).count();
    }

    public int unreadableCount() {
        return unreadableSlots.size();
    }

    public boolean isUnreadable(long serial) {
        return unreadableSlots.contains(serial);
    }

    private static ChunkPos pos(long serial) {
        SerialLayout.Slot slot = SerialLayout.slot(serial);
        return new ChunkPos(slot.x(), slot.z());
    }

    private RegistryOps<Tag> ops() {
        return server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static @Nullable UUID uuid(@Nullable Player player) {
        return player == null ? null : player.getUUID();
    }
}
