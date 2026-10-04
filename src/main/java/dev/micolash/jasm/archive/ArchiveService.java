package dev.micolash.jasm.archive;

import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckWafers;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.JasmState;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferMerge;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * What an Archive does: link and unlink wafers, and rebuild a lost wafer onto a blank. Who may use it is set at an
 * Encoding Terminal on its network (see {@link MachineAccess}).
 * Every action checks everything it needs before changing anything, so a refused action has no effect at all.
 * Links and recoveries are written to disk straight away (see {@link JasmState#saveNow}).
 */
public final class ArchiveService {
    public enum Result {
        OK,
        NOT_READY,
        NO_ACCESS,
        WRONG_DECK,
        NO_WAFER,
        WAFER_LOCKED,
        ALREADY_LINKED,
        FULL,
        NO_POWER,
        /** The network holds more machines than its brain allows. */
        NETWORK_FULL,
        NOT_LINKED_HERE,
        UNREADABLE,
        NOT_BLANK,
        TOO_SMALL,
        NO_ROOM,
        /** Recovery needs the same kind of wafer: Capacity onto Capacity, Type onto Type. */
        WRONG_KIND;

        public String messageKey() {
            return "message.jasm.archive." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** One linked wafer as the Archive screen lists it. {@code readable} is false while its record can't be read. */
    public record Entry(long serial, String name, long used, int capacity, boolean readable, boolean fluid) {}

    /** Overall coverage of the held Deck, including backups made before this action. */
    public record Backup(Result result, int backedUp, int total) {}

    private ArchiveService() {}

    /** Back up wafers in Deck slot order without moving backups already on this network. */
    public static Backup backupDeck(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, ItemStack deck) {
        Result access = access(block, player);
        DeckWafers wafers = DeckItem.wafers(deck);
        int total = wafers.count();
        if (access != Result.OK) return new Backup(access, 0, total);
        if (block.networkStopped()) return new Backup(Result.NETWORK_FULL, 0, total);
        if (!(deck.getItem() instanceof DeckItem)) return new Backup(Result.WRONG_DECK, 0, total);
        ArchiveRecord archive = block.record();
        var network = Networks.at(player.level(), block.getBlockPos());
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (archive.deck() == null && archive.defaultDeck()
                && (network == null || network.machines(EncodingTerminalBlockEntity.class).isEmpty())) {
            if (deckId == null) {
                deckId = UUID.randomUUID();
                deck.set(JasmComponents.DECK_ID.get(), deckId);
            }
            store.state().setArchiveDeck(archive, deckId, player.getUUID(), true);
            store.state().saveNow(player.level().getServer());
        }
        if (deckId == null || !deckId.equals(archive.deck())) return new Backup(Result.WRONG_DECK, 0, total);

        var archives = new HashSet<UUID>();
        archives.add(archive.id());
        if (network != null) {
            for (var pos : network.machines()) {
                if (player.level().getBlockEntity(pos) instanceof ArchiveBlockEntity other && other.archiveId() != null) {
                    archives.add(other.archiveId());
                }
            }
        }
        int backedUp = 0;
        DeckWafers updated = wafers;
        for (DeckWafers.Entry entry : wafers.entries()) {
            ItemStack wafer = entry.wafer().create();
            if (!(wafer.getItem() instanceof WaferItem)) continue;
            Verdict verdict = WaferValidator.validate(store, wafer, WaferValidator.Mode.PASSIVE, null);
            if (verdict == Verdict.VALID || verdict == Verdict.VALID_AHEAD || verdict == Verdict.UNFORMATTED) {
                WaferRecord record = WaferValidator.record(store, wafer).orElse(null);
                if (record != null && archives.contains(record.archiveId())) {
                    backedUp++;
                } else if (link(store, block, player, wafer) == Result.OK) {
                    backedUp++;
                }
            }
            updated = updated.with(entry.slot(), wafer);
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), updated);
        return new Backup(Result.OK, backedUp, total);
    }

    /**
     * The wafers linked to this Archive, oldest link first. Entries whose wafer has since been linked elsewhere, or
     * whose record is gone, are dropped from the Archive's list here.
     */
    public static List<Entry> entries(WaferStore store, ArchiveRecord archive) {
        List<Entry> entries = new ArrayList<>();
        for (long serial : List.copyOf(archive.linked())) {
            Optional<WaferRecord> record = store.bySerial(serial);
            if (record.isEmpty()) {
                if (store.isUnreadable(serial)) {
                    entries.add(new Entry(serial, "", 0, 0, false, false));
                } else {
                    store.state().removeLinked(archive, serial);
                }
            } else if (!archive.id().equals(record.get().archiveId())) {
                store.state().removeLinked(archive, serial);
            } else {
                WaferRecord r = record.get();
                entries.add(new Entry(serial, r.lastKnownName(), r.used(), r.capacity(), true, r.isFluid()));
            }
        }
        return entries;
    }

    /**
     * Links the wafer to this Archive, replacing any earlier link. A blank wafer is formatted first. Costs
     * {@code archive.linkCost} FE.
     */
    public static Result link(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, ItemStack wafer) {
        ArchiveRecord archive = block.record();
        Result access = access(block, player);
        if (access != Result.OK) {
            return access;
        }
        if (!(wafer.getItem() instanceof WaferItem)) {
            return Result.NO_WAFER;
        }
        WaferRecord record = null;
        if (wafer.has(JasmComponents.WAFER_IDENTITY.get()) || WaferMerge.isPending(wafer)) {
            Verdict verdict = WaferValidator.validate(store, wafer, WaferValidator.Mode.PASSIVE, player);
            if (verdict != Verdict.VALID && verdict != Verdict.VALID_AHEAD) {
                return verdict == Verdict.UNFORMATTED ? Result.NO_WAFER : Result.WAFER_LOCKED;
            }
            record = WaferValidator.record(store, wafer).orElseThrow();
            if (archive.id().equals(record.archiveId())) {
                return Result.ALREADY_LINKED;
            }
        }
        if (entries(store, archive).size() >= archive.tier().registrations()) {
            return Result.FULL;
        }
        if (block.networkStopped()) {
            return Result.NETWORK_FULL;
        }
        int cost = JasmConfig.ARCHIVE_LINK_COST.getAsInt();
        if (block.energy().getAmountAsInt() < cost) {
            return Result.NO_POWER;
        }

        if (record == null) {
            record = WaferValidator.format(store, wafer, player);
        }
        UUID previous = record.archiveId();
        if (previous != null) {
            WaferRecord moved = record;
            store.state().archive(previous).ifPresent(old -> store.state().removeLinked(old, moved.serial()));
        }
        store.setLink(record, archive.id(), wafer.getHoverName().getString(), player);
        store.state().addLinked(archive, record.serial());
        spend(block.energy(), cost);
        store.state().saveNow(player.level().getServer());
        return Result.OK;
    }

    /** Removes a wafer's link to this Archive. Free. */
    public static Result unlink(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, long serial) {
        ArchiveRecord archive = block.record();
        Result access = access(block, player);
        if (access != Result.OK) {
            return access;
        }
        Optional<WaferRecord> record = store.bySerial(serial);
        if (record.isEmpty() && store.isUnreadable(serial)) {
            return Result.UNREADABLE;
        }
        if (record.isEmpty() || !archive.id().equals(record.get().archiveId())) {
            store.state().removeLinked(archive, serial);
            return Result.NOT_LINKED_HERE;
        }
        store.setLink(record.get(), null, record.get().lastKnownName(), player);
        store.state().removeLinked(archive, serial);
        store.state().saveNow(player.level().getServer());
        return Result.OK;
    }

    /**
     * Rebuilds a linked wafer onto a blank one. The blank is used up, a new wafer with the same contents goes into
     * the player's inventory, and every older copy of that wafer dissolves the next time it is checked. Costs
     * {@code archive.recoveryCost} FE.
     */
    public static Result recover(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, long serial, ItemStack blank) {
        ArchiveRecord archive = block.record();
        Result access = access(block, player);
        if (access != Result.OK) {
            return access;
        }
        Optional<WaferRecord> found = store.bySerial(serial);
        if (found.isEmpty() && store.isUnreadable(serial)) {
            return Result.UNREADABLE;
        }
        if (found.isEmpty() || !archive.id().equals(found.get().archiveId())) {
            return Result.NOT_LINKED_HERE;
        }
        WaferRecord record = found.get();
        if (!isBlank(store, blank, record)) {
            return Result.NOT_BLANK;
        }
        WaferItem blankItem = (WaferItem) blank.getItem();
        if (blankItem.tier().kind() != record.kind() || blankItem.tier().isTyped() != record.isTyped()) {
            return Result.WRONG_KIND;
        }
        if (!fits(record, blankItem.tier())) {
            return Result.TOO_SMALL;
        }
        if (block.networkStopped()) {
            return Result.NETWORK_FULL;
        }
        int cost = JasmConfig.ARCHIVE_RECOVERY_COST.getAsInt();
        if (block.energy().getAmountAsInt() < cost) {
            return Result.NO_POWER;
        }
        int freeSlot = player.getInventory().getFreeSlot();
        if (freeSlot < 0) {
            return Result.NO_ROOM;
        }

        blank.shrink(1);
        ItemStack replacement = new ItemStack(blankItem);
        replacement.set(JasmComponents.WAFER_IDENTITY.get(),
                new WaferIdentity(record.id(), record.serial(), store.reissue(record, blankItem.tier(), player)));
        player.getInventory().setItem(freeSlot, replacement);
        spend(block.energy(), cost);
        // Saved at once, so every older copy stays dissolved even if the game stops right after.
        store.state().saveNow(player.level().getServer());
        return Result.OK;
    }

    /** Whether a wafer's contents fit a wafer of {@code tier}: the total, and on Type Wafers the types and the biggest type. */
    private static boolean fits(WaferRecord record, WaferTier tier) {
        if (tier.capacityAmount() < record.used()) {
            return false;
        }
        if (!tier.isTyped()) {
            return true;
        }
        long biggest = record.isFluid()
                ? record.fluids().values().stream().mapToLong(Long::longValue).max().orElse(0)
                : record.contents().entrySet().stream()
                        .filter(e -> !TypeRules.isSingle(e.getKey()))
                        .mapToLong(Map.Entry::getValue).max().orElse(0);
        return record.typesUsed() <= tier.types() && biggest <= tier.perTypeAmount();
    }

    /** Unformatted, or formatted with nothing stored and no link (and not the wafer being recovered). */
    public static boolean isBlank(WaferStore store, ItemStack stack, @Nullable WaferRecord recovering) {
        if (!(stack.getItem() instanceof WaferItem) || stack.getCount() != 1 || WaferMerge.isPending(stack)) {
            return false;
        }
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            return true;
        }
        WaferStore.Lookup lookup = store.find(identity);
        WaferRecord record = lookup.record();
        return record != null && record != recovering && record.used() == 0 && record.archiveId() == null
                && record.current().equals(identity.stamp());
    }

    private static Result access(ArchiveBlockEntity block, ServerPlayer player) {
        if (block.record() == null) {
            return Result.NOT_READY;
        }
        return block.canBackup(player) ? Result.OK : Result.NO_ACCESS;
    }

    private static void spend(SimpleEnergyHandler energy, int amount) {
        try (Transaction tx = Transaction.openRoot()) {
            energy.extract(amount, tx);
            tx.commit();
        }
    }
}
