package dev.micolash.jasm.deck;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Everything a Deck does with its wafers, on the server. Every operation first re-checks the wafers, and every
 * change to a wafer or the charge is stored back on the Deck stack in one go.
 */
public final class DeckStorage {
    /** What one wafer slot can do right now. A blank wafer is usable and gets set up on its first deposit. */
    private record SlotView(ItemStack wafer, @Nullable WaferRecord record) implements DepositRouter.Slot<ItemResource> {
        @Override
        public boolean usable() {
            return record != null || isBlank();
        }

        @Override
        public long count(ItemResource key) {
            return record == null ? 0 : record.count(key);
        }

        @Override
        public long free() {
            if (record != null) {
                return record.free();
            }
            return isBlank() ? ((WaferItem) wafer.getItem()).tier().capacity() : 0;
        }

        boolean isBlank() {
            return wafer.getItem() instanceof WaferItem && !wafer.has(JasmComponents.WAFER_IDENTITY.get());
        }
    }

    /** One wafer slot as shown on the Deck screen. */
    public record SlotStatus(boolean present, long used, long capacity, long fromMissingMods, boolean linked) {
        public static final SlotStatus NONE = new SlotStatus(false, 0, 0, 0, false);
    }

    private DeckStorage() {}

    /** Opening the Deck or inserting a wafer: every valid wafer gets a fresh stamp. Copies are blanked. */
    public static List<Verdict> activate(WaferStore store, ItemStack deck, ServerPlayer player) {
        return validateAll(store, deck, player, Mode.ACTIVATE);
    }

    /** Before any grid operation: never mints, but still blanks copies and dissolves recovered originals. */
    public static List<Verdict> checkAll(WaferStore store, ItemStack deck, ServerPlayer player) {
        return validateAll(store, deck, player, Mode.CHECK);
    }

    private static List<Verdict> validateAll(WaferStore store, ItemStack deck, ServerPlayer player, Mode mode) {
        DeckTier tier = ((DeckItem) deck.getItem()).tier();
        DeckWafers wafers = DeckItem.wafers(deck);
        DeckWafers updated = wafers;
        List<Verdict> verdicts = new ArrayList<>();
        for (int slot = 0; slot < tier.slots(); slot++) {
            ItemStack wafer = wafers.get(slot);
            if (wafer.isEmpty() || !(wafer.getItem() instanceof WaferItem)) {
                verdicts.add(Verdict.UNFORMATTED);
                continue;
            }
            ItemStack before = wafer.copy();
            verdicts.add(WaferValidator.validate(store, wafer, mode, player));
            if (!ItemStack.matches(before, wafer)) {
                updated = updated.with(slot, wafer);
            }
        }
        if (updated != wafers) {
            deck.set(JasmComponents.DECK_WAFERS.get(), updated);
        }
        return verdicts;
    }

    /**
     * Stores as much of {@code source} as fits and the charge allows, shrinking {@code source} by the amount
     * stored. Returns the amount stored. If the charge cannot pay for the whole move, nothing moves.
     */
    public static long deposit(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player) {
        WaferEligibility.Result eligible = WaferEligibility.check(source, player.level().registryAccess());
        if (!eligible.accepted()) {
            player.sendOverlayMessage(Component.translatable(eligible.messageKey()));
            return 0;
        }
        List<SlotView> views = views(store, deck, player);
        ItemResource key = ItemResource.of(source);
        List<DepositRouter.Allocation> plan = DepositRouter.planDeposit(views, key, source.getCount());
        long planned = plan.stream().mapToLong(DepositRouter.Allocation::amount).sum();
        if (planned == 0 || !canAfford(deck, planned, player)) {
            return 0;
        }
        DeckWafers wafers = DeckItem.wafers(deck);
        long moved = 0;
        for (DepositRouter.Allocation allocation : plan) {
            SlotView view = views.get(allocation.slot());
            WaferRecord record = view.record();
            if (record == null) {
                ItemStack blank = view.wafer();
                record = WaferValidator.format(store, blank, player);
                wafers = wafers.with(allocation.slot(), blank);
            }
            moved += store.insert(record, key, allocation.amount(), false, player);
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        charge(deck, moved);
        source.shrink((int) moved);
        return moved;
    }

    /** Takes up to {@code amount} of {@code key} out, as stacks no larger than the item allows. */
    public static List<ItemStack> withdraw(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        List<SlotView> views = views(store, deck, player);
        List<DepositRouter.Allocation> plan = DepositRouter.planWithdraw(views, key, amount);
        long planned = plan.stream().mapToLong(DepositRouter.Allocation::amount).sum();
        if (planned == 0 || !canAfford(deck, planned, player)) {
            return List.of();
        }
        long taken = 0;
        for (DepositRouter.Allocation allocation : plan) {
            taken += store.extract(views.get(allocation.slot()).record(), key, allocation.amount(), false, player);
        }
        charge(deck, taken);
        List<ItemStack> stacks = new ArrayList<>();
        int max = key.getMaxStackSize();
        while (taken > 0) {
            int size = (int) Math.min(max, taken);
            stacks.add(key.toStack(size));
            taken -= size;
        }
        return stacks;
    }

    /** Total count of {@code key} across the Deck's usable wafers. Assumes {@link #checkAll} ran this tick. */
    public static long count(WaferStore store, ItemStack deck, ItemResource key) {
        long total = 0;
        for (WaferRecord record : records(store, deck)) {
            if (record != null) {
                total += record.count(key);
            }
        }
        return total;
    }

    /** The usable record in each slot (null for empty, blank, or refused slots). Does not validate. */
    public static List<@Nullable WaferRecord> records(WaferStore store, ItemStack deck) {
        DeckTier tier = ((DeckItem) deck.getItem()).tier();
        DeckWafers wafers = DeckItem.wafers(deck);
        List<@Nullable WaferRecord> records = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int slot = 0; slot < tier.slots(); slot++) {
            records.add(firstUse(usableRecord(store, wafers.get(slot)), seen));
        }
        return records;
    }

    public static List<SlotStatus> status(WaferStore store, ItemStack deck) {
        DeckTier tier = ((DeckItem) deck.getItem()).tier();
        DeckWafers wafers = DeckItem.wafers(deck);
        List<SlotStatus> status = new ArrayList<>();
        for (int slot = 0; slot < tier.slots(); slot++) {
            ItemStack wafer = wafers.get(slot);
            if (!(wafer.getItem() instanceof WaferItem item)) {
                status.add(SlotStatus.NONE);
                continue;
            }
            WaferRecord record = usableRecord(store, wafer);
            status.add(record == null
                    ? new SlotStatus(true, 0, item.tier().capacity(), 0, false)
                    : new SlotStatus(true, record.used(), record.capacity(), record.quarantinedCount(), record.archiveId() != null));
        }
        return status;
    }

    public static boolean hasEnergyFor(ItemStack deck, long items) {
        return DeckItem.energy(deck) >= cost(items);
    }

    private static List<SlotView> views(WaferStore store, ItemStack deck, ServerPlayer player) {
        checkAll(store, deck, player);
        DeckTier tier = ((DeckItem) deck.getItem()).tier();
        DeckWafers wafers = DeckItem.wafers(deck);
        List<SlotView> views = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int slot = 0; slot < tier.slots(); slot++) {
            ItemStack wafer = wafers.get(slot);
            WaferRecord record = firstUse(usableRecord(store, wafer), seen);
            views.add(new SlotView(record == null && wafer.has(JasmComponents.WAFER_IDENTITY.get()) ? ItemStack.EMPTY : wafer, record));
        }
        return views;
    }

    /** Two slots can never both count the same wafer; a second copy is skipped until the next check wipes it. */
    private static @Nullable WaferRecord firstUse(@Nullable WaferRecord record, Set<UUID> seen) {
        return record != null && seen.add(record.id()) ? record : null;
    }

    /** The record behind a wafer that currently grants access, without changing anything. */
    private static @Nullable WaferRecord usableRecord(WaferStore store, ItemStack wafer) {
        if (!(wafer.getItem() instanceof WaferItem item)) {
            return null;
        }
        WaferRecord record = WaferValidator.record(store, wafer).orElse(null);
        if (record == null || record.capacity() != item.tier().capacity()
                || !record.current().equals(wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp())) {
            return null;
        }
        return record;
    }

    private static boolean canAfford(ItemStack deck, long items, ServerPlayer player) {
        if (hasEnergyFor(deck, items)) {
            return true;
        }
        player.sendOverlayMessage(Component.translatable("message.jasm.deck.no_power"));
        return false;
    }

    private static long cost(long items) {
        return JasmConfig.DECK_TRANSFER_BASE_COST.getAsInt() + JasmConfig.DECK_TRANSFER_PER_ITEM_COST.getAsInt() * items;
    }

    private static void charge(ItemStack deck, long items) {
        if (items <= 0) {
            return;
        }
        long left = Math.max(0, DeckItem.energy(deck) - cost(items));
        deck.set(JasmComponents.ENERGY.get(), (int) left);
    }
}
