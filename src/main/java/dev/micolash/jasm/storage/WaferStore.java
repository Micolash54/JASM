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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 * All methods run on the server thread.
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
    static @Nullable WaferStore ifOpen(MinecraftServer server) {
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
        return new Lookup(Status.FOUND, record.get());
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

    /** Creates the record for a freshly formatted wafer. */
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

    /** Stores up to {@code amount}; returns the accepted amount. A null actor means no player was involved. */
    public long insert(WaferRecord record, ItemResource key, long amount, boolean simulate, @Nullable Player actor) {
        long accepted = record.mutableContents().insert(key, amount, record.contentCapacity(), simulate);
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

    private CompletableFuture<Void> write(WaferRecord record) {
        DataResult<Tag> encoded = WaferRecord.CODEC.encodeStart(ops(), record);
        Optional<Tag> result = encoded.result();
        if (result.isEmpty()) {
            Jasm.LOGGER.error("Could not save wafer record #{} ({}); the last saved copy stays on disk", record.serial(),
                    encoded.error().map(e -> e.message()).orElse("?"));
            return CompletableFuture.completedFuture(null);
        }
        CompoundTag slot = new CompoundTag();
        slot.put("record", result.get());
        NbtUtils.addCurrentDataVersion(slot);
        record.written();
        return storage.write(pos(record.serial()), slot).whenComplete((ok, failure) -> {
            if (failure != null) {
                Jasm.LOGGER.error("Could not write wafer record #{}; it will be written again at the next save", record.serial(), failure);
                server.execute(() -> record.changed(null));
            }
        });
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
        storage.synchronize(true).join();
    }

    /** Drops a saved record from memory so its next use reads it from disk, as after a restart. For tests only. */
    boolean unload(long serial) {
        WaferRecord record = records.get(serial);
        if (record == null || record.isDirty()) {
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
