package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.JasmState;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferMerge;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * What an Archive does: link and unlink wafers, rebuild a lost wafer onto a blank, and manage who may use it.
 * Every action checks everything it needs before changing anything, so a refused action has no effect at all.
 * Links and access changes are written to disk straight away (see {@link JasmState#saveNow}).
 */
public final class ArchiveService {
    public enum Result {
        OK,
        NOT_READY,
        NO_ACCESS,
        NOT_OWNER,
        NO_WAFER,
        WAFER_LOCKED,
        ALREADY_LINKED,
        FULL,
        NO_POWER,
        NOT_LINKED_HERE,
        UNREADABLE,
        NOT_BLANK,
        TOO_SMALL,
        NO_ROOM,
        PLAYER_NOT_FOUND,
        IS_OWNER,
        /** A trust request is waiting for the player's name to be looked up; the outcome follows separately. */
        LOOKING_UP;

        public String messageKey() {
            return "message.jasm.archive." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One linked wafer as the Archive screen lists it. {@code readable} is false while its record can't be read. */
    public record Entry(long serial, String name, long used, int capacity, boolean readable) {}

    /** Owners with a name lookup running. One at a time each, so pressing the button again can't pile up lookups. */
    private static final Set<UUID> LOOKUPS = new HashSet<>();

    private ArchiveService() {}

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
                    entries.add(new Entry(serial, "", 0, 0, false));
                } else {
                    store.state().removeLinked(archive, serial);
                }
            } else if (!archive.id().equals(record.get().archiveId())) {
                store.state().removeLinked(archive, serial);
            } else {
                WaferRecord r = record.get();
                entries.add(new Entry(serial, r.lastKnownName(), r.used(), r.capacity(), true));
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
        Result access = access(archive, player);
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
        Result access = access(archive, player);
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
        Result access = access(archive, player);
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
        if (blankItem.tier().capacity() < record.used()) {
            return Result.TOO_SMALL;
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
                new WaferIdentity(record.id(), record.serial(), store.reissue(record, blankItem.tier().capacity(), player)));
        player.getInventory().setItem(freeSlot, replacement);
        spend(block.energy(), cost);
        return Result.OK;
    }

    /**
     * Lets a player use this Archive. Owner only. An online player is trusted straight away. Anyone else is looked
     * up by name in the background (that can mean asking Mojang) and the call returns {@link Result#LOOKING_UP};
     * the outcome then goes to {@code done} on the server thread, after every check has run again.
     */
    public static Result trust(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, String name, UserNameToIdResolver names,
            Consumer<Result> done) {
        ArchiveRecord archive = block.record();
        Result access = ownerOnly(archive, player);
        if (access != Result.OK) {
            return access;
        }
        String wanted = name.trim();
        MinecraftServer server = player.level().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(wanted);
        if (online != null) {
            return trustFound(store, archive, player, new NameAndId(online.getUUID(), online.getPlainTextName()));
        }
        if (!StringUtil.isValidPlayerName(wanted)) {
            return Result.PLAYER_NOT_FOUND;
        }
        if (!LOOKUPS.add(player.getUUID())) {
            return Result.LOOKING_UP;
        }
        UUID archiveId = archive.id();
        CompletableFuture.supplyAsync(() -> names.get(wanted), Util.nonCriticalIoPool()).handleAsync((found, failure) -> {
            LOOKUPS.remove(player.getUUID());
            if (failure != null) {
                Jasm.LOGGER.warn("Could not look up player {}", wanted, failure);
            }
            WaferStore open = WaferStore.ifOpen(server);
            ArchiveRecord current = open == null ? null : open.state().archive(archiveId).orElse(null);
            Result result = found == null || found.isEmpty() ? Result.PLAYER_NOT_FOUND
                    : current == null ? Result.NOT_READY
                    : trustFound(open, current, player, found.get());
            done.accept(result);
            return null;
        }, server);
        return Result.LOOKING_UP;
    }

    private static Result trustFound(WaferStore store, ArchiveRecord archive, ServerPlayer player, NameAndId target) {
        Result access = ownerOnly(archive, player);
        if (access != Result.OK) {
            return access;
        }
        if (target.id().equals(archive.owner())) {
            return Result.IS_OWNER;
        }
        store.state().trust(archive, target.id(), target.name());
        store.state().saveNow(player.level().getServer());
        return Result.OK;
    }

    /** Takes access away again. Owner only. */
    public static Result untrust(WaferStore store, ArchiveBlockEntity block, ServerPlayer player, UUID target) {
        ArchiveRecord archive = block.record();
        Result access = ownerOnly(archive, player);
        if (access != Result.OK) {
            return access;
        }
        store.state().untrust(archive, target);
        store.state().saveNow(player.level().getServer());
        return Result.OK;
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

    private static Result access(@Nullable ArchiveRecord archive, ServerPlayer player) {
        if (archive == null) {
            return Result.NOT_READY;
        }
        return archive.isAuthorized(player.getUUID()) ? Result.OK : Result.NO_ACCESS;
    }

    private static Result ownerOnly(@Nullable ArchiveRecord archive, ServerPlayer player) {
        if (archive == null) {
            return Result.NOT_READY;
        }
        return archive.owner().equals(player.getUUID()) ? Result.OK : Result.NOT_OWNER;
    }

    private static void spend(SimpleEnergyHandler energy, int amount) {
        try (Transaction tx = Transaction.openRoot()) {
            energy.extract(amount, tx);
            tx.commit();
        }
    }
}
