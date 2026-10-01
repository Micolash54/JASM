package dev.micolash.jasm.deck;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferMerge;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        public boolean accepts(ItemResource key) {
            return record == null || record.settings().rank(key.getItem()) >= 0;
        }

        @Override
        public long room(ItemResource key) {
            if (record != null) {
                return record.roomFor(key);
            }
            if (!isBlank()) {
                return 0;
            }
            WaferTier tier = ((WaferItem) wafer.getItem()).tier();
            return tier.isTyped() ? Math.min(tier.capacity(), TypeRules.room(key, 0, 0, tier.types(), tier.perType())) : tier.capacity();
        }

        boolean isBlank() {
            return wafer.getItem() instanceof WaferItem && !wafer.has(JasmComponents.WAFER_IDENTITY.get()) && !WaferMerge.isPending(wafer);
        }
    }

    /** One wafer slot as shown on the Deck screen. {@code types} is 0 for Capacity Wafers. */
    public record SlotStatus(boolean present, long used, long capacity, long fromMissingMods, boolean linked, long typesUsed, int types,
            WaferSettings settings) {
        public static final SlotStatus NONE = new SlotStatus(false, 0, 0, 0, false, 0, 0, WaferSettings.DEFAULT);
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
        return deposit(store, deck, source, player, true);
    }

    /** As {@link #deposit}, but says nothing when an item is refused or the charge is empty (the crafting grid). */
    public static long depositQuietly(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player) {
        return deposit(store, deck, source, player, false);
    }

    private static long deposit(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player, boolean tell) {
        if (!checkDimension(deck, player, tell)) return 0;
        if (source.isEmpty()) {
            return 0;
        }
        WaferEligibility.Result eligible = WaferEligibility.check(source, player.level().registryAccess());
        if (!eligible.accepted()) {
            if (tell) {
                Notices.bad(player, Component.translatable(eligible.messageKey()));
            }
            return 0;
        }
        List<SlotView> views = views(store, deck, player);
        ItemResource key = ItemResource.of(source);
        List<DepositRouter.Allocation> plan = DepositRouter.planDeposit(views, key, source.getCount());
        long planned = plan.stream().mapToLong(DepositRouter.Allocation::amount).sum();
        if (planned == 0 || !canAfford(deck, player, tell)) {
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
        source.shrink((int) moved);
        return moved;
    }

    /**
     * Sets how the Deck routes items to the wafer in {@code slot}. A blank wafer is set up first, as on its first
     * deposit. Returns false if there is no wafer there, or it can't be used right now.
     */
    public static boolean configure(WaferStore store, ItemStack deck, int slot, WaferSettings settings, ServerPlayer player) {
        if (!checkDimension(deck, player, true)) return false;
        List<SlotView> views = views(store, deck, player);
        if (slot < 0 || slot >= views.size() || !views.get(slot).usable()) {
            return false;
        }
        SlotView view = views.get(slot);
        List<WaferSettings.Filter> previous = view.record() == null ? List.of() : view.record().settings().rules();
        if (settings.rules().stream().anyMatch(rule -> !rule.valid() && previous.stream()
                .noneMatch(old -> old.mode() == rule.mode() && old.value().equals(rule.value())))) return false;
        WaferRecord record = view.record();
        if (record == null) {
            ItemStack blank = view.wafer();
            record = WaferValidator.format(store, blank, player);
            deck.set(JasmComponents.DECK_WAFERS.get(), DeckItem.wafers(deck).with(slot, blank));
        }
        store.setSettings(record, settings, player);
        return true;
    }

    /**
     * Stores up to {@code amount} of {@code key} on the Deck's wafers, straight from somewhere else in the store (a
     * crafting job), without needing the Deck's charge. Returns how many were stored; items wafers refuse store none.
     */
    public static long depositAmount(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        if (!DeckItem.worksIn(deck, player.level())) return 0;
        if (amount <= 0 || key.isEmpty() || !WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) {
            return 0;
        }
        List<SlotView> views = views(store, deck, player);
        List<DepositRouter.Allocation> plan = DepositRouter.planDeposit(views, key, amount);
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
        return moved;
    }

    /** Fill wafers left to right, choosing each wafer's highest matching rows before other items in the batch. */
    public static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items, ServerPlayer player) {
        return depositAmounts(store, deck, items, player, Long.MAX_VALUE);
    }

    /** Same routing order, with a shared limit on the number of items moved. */
    public static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items, ServerPlayer player, long limit) {
        if (!DeckItem.worksIn(deck, player.level())) return Map.of();
        var left = new java.util.LinkedHashMap<ItemResource, Long>();
        items.forEach((key, amount) -> {
            if (amount > 0 && !key.isEmpty() && WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) left.put(key, amount);
        });
        var moved = new java.util.LinkedHashMap<ItemResource, Long>();
        List<SlotView> slots = views(store, deck, player);
        DeckWafers wafers = DeckItem.wafers(deck);
        for (int i = 0; i < slots.size(); i++) {
            SlotView slot = slots.get(i);
            if (!slot.usable()) continue;
            WaferRecord record = slot.record();
            WaferSettings settings = record == null ? WaferSettings.DEFAULT : record.settings();
            List<ItemResource> order = new ArrayList<>(left.keySet());
            order.removeIf(key -> settings.rank(key.getItem()) < 0);
            order.sort(java.util.Comparator.comparingInt(key -> settings.rank(key.getItem())));
            for (ItemResource key : order) {
                if (limit <= 0) break;
                long remaining = left.get(key);
                if (remaining <= 0 || (record == null ? slot.room(key) : record.roomFor(key)) <= 0) continue;
                if (record == null) {
                    ItemStack blank = slot.wafer();
                    record = WaferValidator.format(store, blank, player);
                    wafers = wafers.with(i, blank);
                }
                long stored = store.insert(record, key, Math.min(remaining, limit), false, player);
                if (stored > 0) {
                    limit -= stored;
                    moved.merge(key, stored, Long::sum);
                    left.put(key, remaining - stored);
                }
            }
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        return moved;
    }

    /** How many of {@code key}, up to {@code amount}, the Deck's wafers would take. Changes nothing. */
    public static long room(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        if (!DeckItem.worksIn(deck, player.level())) return 0;
        if (amount <= 0 || key.isEmpty() || !WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) {
            return 0;
        }
        return DepositRouter.planDeposit(views(store, deck, player), key, amount).stream().mapToLong(DepositRouter.Allocation::amount).sum();
    }

    /** Everything on the Deck's usable wafers, added up. Assumes {@link #checkAll} ran this tick. */
    public static Map<ItemResource, Long> contents(WaferStore store, ItemStack deck) {
        Map<ItemResource, Long> total = new java.util.LinkedHashMap<>();
        for (WaferRecord record : records(store, deck)) {
            if (record != null) {
                record.contents().forEach((key, count) -> total.merge(key, count, Long::sum));
            }
        }
        return total;
    }

    /** Takes up to {@code amount} of {@code key} out, as stacks no larger than the item allows. */
    public static List<ItemStack> withdraw(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return withdraw(store, deck, key, amount, player, true);
    }

    /** As {@link #withdraw}, but says nothing when the charge is empty (refilling the crafting grid). */
    public static List<ItemStack> withdrawQuietly(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return withdraw(store, deck, key, amount, player, false);
    }

    private static List<ItemStack> withdraw(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            boolean tell) {
        if (!checkDimension(deck, player, tell)) return List.of();
        if (amount <= 0 || key.isEmpty()) {
            return List.of();
        }
        List<SlotView> views = views(store, deck, player);
        List<DepositRouter.Allocation> plan = DepositRouter.planWithdraw(views, key, amount);
        long planned = plan.stream().mapToLong(DepositRouter.Allocation::amount).sum();
        if (planned == 0 || !canAfford(deck, player, tell)) {
            return List.of();
        }
        long taken = 0;
        for (DepositRouter.Allocation allocation : plan) {
            taken += store.extract(views.get(allocation.slot()).record(), key, allocation.amount(), false, player);
        }
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
                    ? new SlotStatus(true, 0, item.tier().capacity(), 0, false, 0, item.tier().types(), WaferSettings.DEFAULT)
                    : new SlotStatus(true, record.used(), record.capacity(), record.quarantinedCount(), record.archiveId() != null,
                            record.typesUsed(), record.types(), record.settings()));
        }
        return status;
    }

    /** Browsing and moving items only need the battery not to be empty; the Deck pays per tick while open. */
    public static boolean hasPower(ItemStack deck) {
        return DeckItem.energy(deck) > 0;
    }

    /** One tick of an open Deck: its battery runs down by the tier's drain. */
    public static void drain(ItemStack deck) {
        if (deck.getItem() instanceof DeckItem item && item.tier().drainPerTick() > 0) {
            int energy = DeckItem.energy(deck);
            if (energy > 0) {
                deck.set(JasmComponents.ENERGY.get(), Math.max(0, energy - item.tier().drainPerTick()));
            }
        }
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
        if (record == null || record.capacity() != item.tier().capacity() || record.types() != item.tier().types()
                || !record.current().equals(wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp())) {
            return null;
        }
        return record;
    }

    private static boolean canAfford(ItemStack deck, ServerPlayer player, boolean tell) {
        if (hasPower(deck)) {
            return true;
        }
        if (tell) {
            Notices.bad(player, Component.translatable("message.jasm.deck.no_power"));
        }
        return false;
    }

    private static boolean checkDimension(ItemStack deck, ServerPlayer player, boolean tell) {
        if (DeckItem.worksIn(deck, player.level())) return true;
        if (tell) Notices.bad(player, Component.translatable("message.jasm.deck.dimension_upgrade"));
        return false;
    }
}
