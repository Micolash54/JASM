package dev.micolash.jasm.deck;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.core.PoolRouter;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.pool.PoolStore;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferMerge;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Everything a Deck does with its wafers, on the server. Every operation first re-checks the wafers, and every
 * change to a wafer or the charge is stored back on the Deck stack in one go.
 */
public final class DeckStorage {
    /** What one wafer slot can do right now. A blank wafer is usable and gets set up on its first deposit. */
    record SlotView(ItemStack wafer, @Nullable WaferRecord record) implements DepositRouter.Slot<ItemResource> {
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
            if (tier.isFluid()) {
                return 0;
            }
            return tier.isTyped() ? Math.min(tier.capacity(), TypeRules.room(key, 0, 0, tier.types(), tier.perType())) : tier.capacity();
        }

        boolean isBlank() {
            return wafer.getItem() instanceof WaferItem && !wafer.has(JasmComponents.WAFER_IDENTITY.get()) && !WaferMerge.isPending(wafer);
        }
    }

    /**
     * One wafer slot as shown on the Deck screen. {@code types} is 0 for Capacity Wafers. Amounts are in the wafer's
     * own unit: items, or millibuckets when {@code fluid} is set.
     */
    public record SlotStatus(boolean present, long used, long capacity, long fromMissingMods, boolean linked, long typesUsed, int types,
            WaferSettings settings, boolean fluid) {
        public static final SlotStatus NONE = new SlotStatus(false, 0, 0, 0, false, 0, 0, WaferSettings.DEFAULT, false);
    }

    /** What happens to items no wafer has room for: they stay where they were, or a Void row destroys them. */
    public enum Excess { KEEP, VOID }

    /** What a deposit did: {@code stored} on the wafers (or Storage Ports), {@code voided} destroyed. */
    public record Deposit(long stored, long voided) {
        public long total() { return stored + voided; }
    }

    private DeckStorage() {}

    /** Shares one wafer check between the reads and transfers in an operation. */
    public static Checked checked(WaferStore store, ItemStack deck, ServerPlayer player) {
        return new Checked(store, deck, player);
    }

    public static final class Checked {
        private final WaferStore store;
        private final ItemStack deck;
        private final ServerPlayer player;
        private DeckWafers wafers;
        private int tick;
        /** A block the caller is itself moving items through: the pool leaves it alone. */
        @Nullable BlockPos avoid;

        private Checked(WaferStore store, ItemStack deck, ServerPlayer player) {
            this.store = store;
            this.deck = deck;
            this.player = player;
            checkAll(store, deck, player);
            remember();
        }

        private void remember() {
            wafers = DeckItem.wafers(deck);
            tick = player.level().getServer().getTickCount();
        }

        private void check() {
            if (tick != player.level().getServer().getTickCount() || wafers != DeckItem.wafers(deck)) {
                checkAll(store, deck, player);
                remember();
            }
        }

        /** Never moves items or fluid into or out of the block at {@code chest}, through the pool. */
        public Checked avoiding(BlockPos chest) {
            avoid = chest;
            return this;
        }

        public long count(ItemResource key) {
            check();
            return DeckStorage.count(store, deck, key, player);
        }

        public Map<ItemResource, Long> contents() {
            check();
            return DeckStorage.contents(store, deck, player);
        }

        /** Puts back items a move could not finish: wafers first whatever their filters say, then wherever there is room. */
        public long restoreQuietly(ItemResource key, long amount) {
            check();
            long left = amount;
            for (var record : DeckStorage.records(store, deck)) {
                if (record != null && left > 0) left -= store.insert(record, key, left, false, player);
            }
            if (left > 0) left -= depositAmount(key, left);
            remember();
            return amount - left;
        }

        public List<ItemStack> withdraw(ItemResource key, long amount) {
            return DeckStorage.withdraw(store, deck, key, amount, player, true, this);
        }

        public List<ItemStack> withdrawQuietly(ItemResource key, long amount) {
            return DeckStorage.withdraw(store, deck, key, amount, player, false, this);
        }

        public long room(ItemResource key, long amount) {
            return DeckStorage.room(store, deck, key, amount, player, this);
        }

        public long depositAmount(ItemResource key, long amount) {
            long moved = DeckStorage.depositAmount(store, deck, key, amount, player, this);
            if (moved > 0) remember();
            return moved;
        }

        public long countFluid(FluidResource key) {
            check();
            return DeckFluidStorage.count(store, deck, key, player);
        }

        public Map<FluidResource, Long> fluidContents() {
            check();
            return DeckFluidStorage.contents(store, deck, player);
        }

        /** Puts back fluid a move could not finish: wafers first whatever their filters say, then wherever there is room. */
        public long restoreQuietlyFluid(FluidResource key, long amount) {
            check();
            long left = amount;
            for (var record : DeckStorage.records(store, deck)) {
                if (record != null && left > 0) left -= store.insertFluid(record, key, left, false, player);
            }
            if (left > 0) left -= depositFluid(key, left);
            remember();
            return amount - left;
        }

        /** Millibuckets of {@code key}, up to {@code amount}, the Deck's wafers would take. */
        public long roomFluid(FluidResource key, long amount) {
            return DeckFluidStorage.room(store, deck, key, amount, player, this);
        }

        /** Stores up to {@code amount} millibuckets, as far as the charge pays for. Returns the amount stored. */
        public long depositFluid(FluidResource key, long amount) {
            long moved = DeckFluidStorage.deposit(store, deck, key, amount, player, this);
            if (moved > 0) remember();
            return moved;
        }

        /** Takes up to {@code amount} millibuckets out, as far as the charge pays for. Returns the amount taken. */
        public long withdrawFluid(FluidResource key, long amount, boolean tell) {
            return DeckFluidStorage.withdraw(store, deck, key, amount, player, tell, this);
        }

        public Map<ItemResource, Long> depositAmounts(Map<ItemResource, Long> items) {
            return depositAmounts(items, Long.MAX_VALUE);
        }

        public Map<ItemResource, Long> depositAmounts(Map<ItemResource, Long> items, long limit) {
            return depositAmounts(items, limit, Excess.KEEP);
        }

        public Map<ItemResource, Long> depositAmounts(Map<ItemResource, Long> items, Excess excess) {
            return depositAmounts(items, Long.MAX_VALUE, excess);
        }

        public Map<ItemResource, Long> depositAmounts(Map<ItemResource, Long> items, long limit, Excess excess) {
            Map<ItemResource, Long> moved = DeckStorage.depositAmounts(store, deck, items, player, limit, this, excess);
            if (!moved.isEmpty()) remember();
            return moved;
        }

        /** As {@link #room(ItemResource, long)}; with {@code Excess.VOID} a voiding item counts as having room for all of {@code amount}. */
        public long room(ItemResource key, long amount, Excess excess) {
            long room = room(key, amount);
            if (excess != Excess.VOID || room >= amount || !DeckStorage.canVoid(store, deck, key, player, this)) return room;
            return Math.min(amount, DeckStorage.affordable(deck));
        }

        public Deposit depositResult(ItemResource key, long amount, Excess excess) {
            Deposit done = DeckStorage.depositResult(store, deck, key, amount, player, excess, this);
            if (done.stored() > 0) remember();
            return done;
        }

        /** As {@link #roomFluid(FluidResource, long)}; with {@code Excess.VOID} a voiding fluid counts as having room for all of {@code amount}. */
        public long roomFluid(FluidResource key, long amount, Excess excess) {
            long room = roomFluid(key, amount);
            if (excess != Excess.VOID || room >= amount || !DeckFluidStorage.canVoid(store, deck, key, player, this)) return room;
            return Math.min(amount, DeckStorage.affordableFluid(deck));
        }

        public Deposit depositFluidResult(FluidResource key, long amount, Excess excess) {
            Deposit done = DeckFluidStorage.depositResult(store, deck, key, amount, player, excess, this);
            if (done.stored() > 0) remember();
            return done;
        }

        /** Other mods' materials in the network's storage blocks, by exact resource. */
        public Map<Material, Long> materialStacks() {
            return DeckMaterialStorage.stacks(player, deck, avoid);
        }

        public long roomMaterial(Material material, long amount) {
            return DeckMaterialStorage.room(player, deck, material, amount, avoid);
        }

        /** Puts material into the storage blocks as far as the charge pays for. Returns how much went in. */
        public long depositMaterial(Material material, long amount) {
            return DeckMaterialStorage.deposit(player, deck, material, amount, avoid);
        }

        /** Takes material out of the storage blocks as far as the charge pays for. Returns how much came out. */
        public long withdrawMaterial(Material material, long amount) {
            return DeckMaterialStorage.withdraw(player, deck, material, amount, avoid);
        }

        public long restoreMaterial(Material material, long amount) {
            return DeckMaterialStorage.restore(player, deck, material, amount, avoid);
        }

        public long takeBackMaterial(Material material, long amount) {
            return DeckMaterialStorage.takeBack(player, deck, material, amount, avoid);
        }
    }

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
     * Stores as much of {@code source} as fits and the charge pays for, shrinking {@code source} by the amount
     * stored. Returns the amount stored.
     */
    public static long deposit(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player) {
        return deposit(store, deck, source, player, true, Excess.KEEP);
    }

    /** As {@link #deposit}; with {@code Excess.VOID} a Void row destroys what no wafer has room for. Returns the amount taken from {@code source}. */
    public static long deposit(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player, Excess excess) {
        return deposit(store, deck, source, player, true, excess);
    }

    /** As {@link #deposit}, but says nothing when an item is refused or the charge is empty (the crafting grid). */
    public static long depositQuietly(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player) {
        return deposit(store, deck, source, player, false, Excess.KEEP);
    }

    public static long depositQuietly(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player, Excess excess) {
        return deposit(store, deck, source, player, false, excess);
    }

    private static long deposit(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player, boolean tell, Excess excess) {
        long stored = depositToWafers(store, deck, source, player, tell);
        if (excess != Excess.VOID || source.isEmpty()) return stored;
        long gone = voidLeft(store, deck, player, null, ItemResource.of(source), source.getCount());
        source.shrink((int) gone);
        return stored + gone;
    }

    private static long depositToWafers(WaferStore store, ItemStack deck, ItemStack source, ServerPlayer player, boolean tell) {
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
        List<PoolStore> ports = ports(player, deck, null);
        if (!ports.isEmpty()) {
            if (!canAfford(deck, player, tell)) return 0;
            long moved = depositCombined(store, deck, ItemResource.of(source), Math.min(source.getCount(), affordable(deck)), player, null, ports);
            source.shrink((int) moved);
            return moved;
        }
        List<SlotView> views = views(store, deck, player);
        ItemResource key = ItemResource.of(source);
        List<DepositRouter.Allocation> plan = DepositRouter.planDeposit(views, key, source.getCount());
        if (plan.isEmpty() || !canAfford(deck, player, tell)) {
            return 0;
        }
        long affordable = affordable(deck);
        if (affordable < plan.stream().mapToLong(DepositRouter.Allocation::amount).sum()) {
            plan = DepositRouter.planDeposit(views, key, affordable);
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
        pay(deck, moved);
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
                .noneMatch(old -> old.mode() == rule.mode() && old.value().equals(rule.value()))))
            return false;
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
     * Stores up to {@code amount} of {@code key} on the Deck's wafers, straight from somewhere else (a port), as far
     * as the charge pays for. Returns how many were stored; items wafers refuse store none.
     */
    public static long depositAmount(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return depositAmount(store, deck, key, amount, player, null);
    }

    private static long depositAmount(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            @Nullable Checked checked) {
        if (!DeckItem.worksIn(deck, player.level())) return 0;
        if (amount <= 0 || key.isEmpty() || !WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) {
            return 0;
        }
        amount = Math.min(amount, affordable(deck));
        if (amount <= 0) {
            return 0;
        }
        List<PoolStore> ports = ports(player, deck, checked == null ? null : checked.avoid);
        if (!ports.isEmpty()) return depositCombined(store, deck, key, amount, player, checked, ports);
        List<SlotView> views = views(store, deck, player, checked);
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
        pay(deck, moved);
        return moved;
    }

    /** As {@link #depositAmount}, with a Void row destroying what no wafer has room for. */
    public static Deposit depositResult(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player, Excess excess) {
        return depositResult(store, deck, key, amount, player, excess, null);
    }

    private static Deposit depositResult(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player, Excess excess,
            @Nullable Checked checked) {
        long stored = depositAmount(store, deck, key, amount, player, checked);
        long gone = excess == Excess.VOID ? voidLeft(store, deck, player, checked, key, amount - stored) : 0;
        return new Deposit(stored, gone);
    }

    /** Fill wafers left to right, choosing each wafer's highest matching rows before other items in the batch. */
    public static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items, ServerPlayer player) {
        return depositAmounts(store, deck, items, player, Long.MAX_VALUE);
    }

    /**
     * Same routing order, with a shared limit on the number of items moved. The charge limits it too. When the network
     * has Storage Ports, items go in one key at a time in the combined order, so the wafers' row ranking does not apply.
     */
    public static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items, ServerPlayer player,
            long limit) {
        return depositAmounts(store, deck, items, player, limit, null, Excess.KEEP);
    }

    /** As the batch above; with {@code Excess.VOID} the map holds what was stored plus what a Void row destroyed, per item. */
    public static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items, ServerPlayer player,
            long limit, Excess excess) {
        return depositAmounts(store, deck, items, player, limit, null, excess);
    }

    private static Map<ItemResource, Long> depositAmounts(WaferStore store, ItemStack deck, Map<ItemResource, Long> items,
            ServerPlayer player, long limit, @Nullable Checked checked, Excess excess) {
        if (!DeckItem.worksIn(deck, player.level())) return Map.of();
        limit = Math.min(limit, affordable(deck));
        List<PoolStore> ports = ports(player, deck, checked == null ? null : checked.avoid);
        if (!ports.isEmpty()) {
            var batch = new LinkedHashMap<ItemResource, Long>();
            for (var entry : items.entrySet()) {
                if (limit <= 0) break;
                long asked = entry.getValue();
                if (asked <= 0 || entry.getKey().isEmpty()
                        || !WaferEligibility.check(entry.getKey().toStack(1), player.level().registryAccess()).accepted()) continue;
                long ask = Math.min(asked, limit);
                long stored = depositCombined(store, deck, entry.getKey(), ask, player, checked, ports);
                long gone = excess == Excess.VOID ? voidLeft(store, deck, player, checked, entry.getKey(), ask - stored) : 0;
                long handled = stored + gone;
                if (handled > 0) {
                    limit -= handled;
                    batch.put(entry.getKey(), handled);
                }
            }
            return batch;
        }
        long paid = limit;
        var left = new LinkedHashMap<ItemResource, Long>();
        items.forEach((key, amount) -> {
            if (amount > 0 && !key.isEmpty() && WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted())
                left.put(key, amount);
        });
        var moved = new LinkedHashMap<ItemResource, Long>();
        List<SlotView> slots = views(store, deck, player, checked);
        DeckWafers wafers = DeckItem.wafers(deck);
        for (int i = 0; i < slots.size(); i++) {
            SlotView slot = slots.get(i);
            if (!slot.usable()) continue;
            WaferRecord record = slot.record();
            WaferSettings settings = record == null ? WaferSettings.DEFAULT : record.settings();
            List<ItemResource> order = new ArrayList<>(left.keySet());
            order.removeIf(key -> settings.rank(key.getItem()) < 0);
            order.sort(Comparator.comparingInt(key -> settings.rank(key.getItem())));
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
        if (excess == Excess.VOID) {
            for (var entry : left.entrySet()) {
                if (limit <= 0) break;
                long gone = Math.min(entry.getValue(), limit);
                if (gone > 0 && canVoid(store, deck, entry.getKey(), player, checked)) {
                    limit -= gone;
                    moved.merge(entry.getKey(), gone, Long::sum);
                }
            }
        }
        pay(deck, paid - limit);
        return moved;
    }

    /** How many of {@code key}, up to {@code amount}, the Deck's wafers would take. Changes nothing. */
    public static long room(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return room(store, deck, key, amount, player, null);
    }

    private static long room(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            @Nullable Checked checked) {
        if (!DeckItem.worksIn(deck, player.level())) return 0;
        if (amount <= 0 || key.isEmpty() || !WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) {
            return 0;
        }
        List<PoolStore> ports = ports(player, deck, checked == null ? null : checked.avoid);
        if (!ports.isEmpty()) return roomCombined(store, deck, key, amount, player, checked, ports);
        return DepositRouter.planDeposit(views(store, deck, player, checked), key, amount).stream().mapToLong(DepositRouter.Allocation::amount).sum();
    }

    /** Everything on the Deck's usable wafers, added up (wafers only). Assumes {@link #checkAll} ran this tick. */
    public static Map<ItemResource, Long> contents(WaferStore store, ItemStack deck) {
        Map<ItemResource, Long> total = new LinkedHashMap<>();
        for (WaferRecord record : records(store, deck)) {
            if (record != null) {
                record.contents().forEach((key, count) -> total.merge(key, count, Long::sum));
            }
        }
        return total;
    }

    /** Takes up to {@code amount} of {@code key} out, as stacks no larger than the item allows. */
    public static List<ItemStack> withdraw(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return withdraw(store, deck, key, amount, player, true, null);
    }

    /** As {@link #withdraw}, but says nothing when the charge is empty (refilling the crafting grid). */
    public static List<ItemStack> withdrawQuietly(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player) {
        return withdraw(store, deck, key, amount, player, false, null);
    }

    private static List<ItemStack> withdraw(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            boolean tell, @Nullable Checked checked) {
        if (!checkDimension(deck, player, tell)) return List.of();
        if (amount <= 0 || key.isEmpty()) {
            return List.of();
        }
        List<PoolStore> ports = ports(player, deck, checked == null ? null : checked.avoid);
        if (!ports.isEmpty()) {
            if (!canAfford(deck, player, tell)) return List.of();
            long got = withdrawCombined(store, deck, key, Math.min(amount, affordable(deck)), player, checked, ports);
            List<ItemStack> stacks = new ArrayList<>();
            int max = key.getMaxStackSize();
            while (got > 0) {
                int size = (int) Math.min(max, got);
                stacks.add(key.toStack(size));
                got -= size;
            }
            return stacks;
        }
        List<SlotView> views = views(store, deck, player, checked);
        List<DepositRouter.Allocation> plan = DepositRouter.planWithdraw(views, key, amount);
        if (plan.isEmpty() || !canAfford(deck, player, tell)) {
            return List.of();
        }
        long affordable = affordable(deck);
        if (affordable < plan.stream().mapToLong(DepositRouter.Allocation::amount).sum()) {
            plan = DepositRouter.planWithdraw(views, key, affordable);
        }
        long taken = 0;
        for (DepositRouter.Allocation allocation : plan) {
            taken += store.extract(views.get(allocation.slot()).record(), key, allocation.amount(), false, player);
        }
        pay(deck, taken);
        List<ItemStack> stacks = new ArrayList<>();
        int max = key.getMaxStackSize();
        while (taken > 0) {
            int size = (int) Math.min(max, taken);
            stacks.add(key.toStack(size));
            taken -= size;
        }
        return stacks;
    }

    /** Total count of {@code key} across the Deck's usable wafers (wafers only). Assumes {@link #checkAll} ran this tick. */
    public static long count(WaferStore store, ItemStack deck, ItemResource key) {
        long total = 0;
        for (WaferRecord record : records(store, deck)) {
            if (record != null) {
                total += record.count(key);
            }
        }
        return total;
    }

    /** Wafers and the network's storage blocks added up. Assumes {@link #checkAll} ran this tick. */
    public static long count(WaferStore store, ItemStack deck, ItemResource key, ServerPlayer player) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return count(store, deck, key) + (pool == null ? 0 : pool.count(key));
    }

    /** Everything on the wafers and in the network's storage blocks, as a fresh map. Assumes {@link #checkAll} ran this tick. */
    public static Map<ItemResource, Long> contents(WaferStore store, ItemStack deck, ServerPlayer player) {
        Map<ItemResource, Long> total = contents(store, deck);
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        if (pool != null) pool.contents().forEach((key, count) -> total.merge(key, count, Long::sum));
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
                    ? new SlotStatus(true, 0, item.tier().capacityAmount(), 0, false, 0, item.tier().types(), WaferSettings.DEFAULT,
                            item.tier().isFluid())
                    : new SlotStatus(true, record.used(), record.capacityAmount(), record.quarantinedCount(), record.archiveId() != null,
                            record.typesUsed(), record.types(), record.settings(), record.isFluid()));
        }
        return status;
    }

    /** Whether the battery pays for at least one item. Browsing is free. */
    public static boolean hasPower(ItemStack deck) {
        return affordable(deck) > 0;
    }

    /** How many items the charge pays for right now. */
    public static long affordable(ItemStack deck) {
        int cost = JasmConfig.DECK_ENERGY_PER_ITEM.getAsInt();
        return cost <= 0 ? Long.MAX_VALUE : DeckItem.energy(deck) / cost;
    }

    /** How many millibuckets the charge pays for right now: an item's charge moves one share of fluid. */
    public static long affordableFluid(ItemStack deck) {
        long items = affordable(deck);
        return items > Long.MAX_VALUE / FluidAmounts.PER_SHARE ? Long.MAX_VALUE : items * FluidAmounts.PER_SHARE;
    }

    /** Whether a Void row would destroy {@code key}: the Deck works here, the item is allowed in, and a usable item wafer's deciding row is Allow with Void. */
    static boolean canVoid(WaferStore store, ItemStack deck, ItemResource key, ServerPlayer player, @Nullable Checked checked) {
        if (key.isEmpty() || !DeckItem.worksIn(deck, player.level())) return false;
        if (!WaferEligibility.check(key.toStack(1), player.level().registryAccess()).accepted()) return false;
        for (SlotView view : views(store, deck, player, checked)) {
            // Rows on a fluid wafer are about fluids; that wafer never holds items.
            if (view.record() != null && !view.record().isFluid() && view.record().settings().voidsExcess(key.getItem())) return true;
        }
        return false;
    }

    /** Destroys {@code left} of {@code key} when a Void row says so, as far as the charge pays. Returns how many were destroyed. */
    private static long voidLeft(WaferStore store, ItemStack deck, ServerPlayer player, @Nullable Checked checked, ItemResource key, long left) {
        left = Math.min(left, affordable(deck));
        if (left <= 0 || !canVoid(store, deck, key, player, checked)) return 0;
        pay(deck, left);
        return left;
    }

    /** Takes the charge for {@code items} moved in or out. */
    static void pay(ItemStack deck, long items) {
        long cost = items * JasmConfig.DECK_ENERGY_PER_ITEM.getAsInt();
        if (cost > 0) {
            deck.set(JasmComponents.ENERGY.get(), (int) Math.max(0, DeckItem.energy(deck) - cost));
        }
    }

    /** A wafer slot or a storage block, for ordering when the network has storage blocks. */
    private record Unit(int index, @Nullable SlotView slot, @Nullable PoolStore port) implements PoolRouter.Unit<ItemResource> {
        @Override
        public int priority() { return port == null ? 0 : port.priority(); }
        @Override
        public boolean wafer() { return port == null; }
        @Override
        public boolean prefers(ItemResource key) { return port == null ? slot.count(key) > 0 : port.prefers(key); }
        @Override
        public boolean canRead() { return port == null ? slot.usable() : port.canRead(); }
        @Override
        public boolean canWrite() { return port == null ? slot.usable() : port.canWrite(); }
    }

    private static List<Unit> units(List<SlotView> views, List<PoolStore> ports) {
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < views.size(); i++) units.add(new Unit(i, views.get(i), null));
        for (PoolStore port : ports) units.add(new Unit(-1, null, port));
        return units;
    }

    /** The storage blocks this Deck may use, or an empty list. {@code avoid} is a block the caller is moving items through. */
    private static List<PoolStore> ports(ServerPlayer player, ItemStack deck, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return pool == null ? List.of() : pool.stores(avoid);
    }

    private static long depositCombined(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            @Nullable Checked checked, List<PoolStore> ports) {
        List<SlotView> views = views(store, deck, player, checked);
        DeckWafers wafers = DeckItem.wafers(deck);
        long moved = 0;
        for (Unit unit : PoolRouter.insertOrder(units(views, ports), key)) {
            long left = amount - moved;
            if (left <= 0) break;
            if (unit.port() != null) {
                moved += unit.port().insertNow(key, left);
                continue;
            }
            SlotView view = unit.slot();
            long take = view.accepts(key) ? Math.min(left, view.room(key)) : 0;
            if (take <= 0) continue;
            WaferRecord record = view.record();
            if (record == null) {
                ItemStack blank = view.wafer();
                record = WaferValidator.format(store, blank, player);
                wafers = wafers.with(unit.index(), blank);
            }
            moved += store.insert(record, key, take, false, player);
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        pay(deck, moved);
        return moved;
    }

    private static long withdrawCombined(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            @Nullable Checked checked, List<PoolStore> ports) {
        List<SlotView> views = views(store, deck, player, checked);
        long taken = 0;
        for (Unit unit : PoolRouter.extractOrder(units(views, ports), key)) {
            long left = amount - taken;
            if (left <= 0) break;
            if (unit.port() != null) taken += unit.port().extractNow(key, left);
            else if (unit.slot().record() != null)
                taken += store.extract(unit.slot().record(), key, Math.min(left, unit.slot().count(key)), false, player);
        }
        pay(deck, taken);
        return taken;
    }

    private static long roomCombined(WaferStore store, ItemStack deck, ItemResource key, long amount, ServerPlayer player,
            @Nullable Checked checked, List<PoolStore> ports) {
        long room = 0;
        for (Unit unit : PoolRouter.insertOrder(units(views(store, deck, player, checked), ports), key)) {
            long left = amount - room;
            if (left <= 0) break;
            room += unit.port() != null ? unit.port().room(key, left)
                    : unit.slot().accepts(key) ? Math.min(left, unit.slot().room(key)) : 0;
        }
        return room;
    }

    private static List<SlotView> views(WaferStore store, ItemStack deck, ServerPlayer player) {
        return views(store, deck, player, null);
    }

    static List<SlotView> views(WaferStore store, ItemStack deck, ServerPlayer player, @Nullable Checked checked) {
        if (checked == null) checkAll(store, deck, player);
        else checked.check();
        return viewsUnchecked(store, deck);
    }

    private static List<SlotView> viewsUnchecked(WaferStore store, ItemStack deck) {
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
                || record.kind() != item.tier().kind()
                || !record.current().equals(wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp())) {
            return null;
        }
        return record;
    }

    static boolean canAfford(ItemStack deck, ServerPlayer player, boolean tell) {
        if (hasPower(deck)) {
            return true;
        }
        if (tell) {
            Notices.bad(player, Component.translatable("message.jasm.deck.no_power"));
        }
        return false;
    }

    static boolean checkDimension(ItemStack deck, ServerPlayer player, boolean tell) {
        if (DeckItem.worksIn(deck, player.level())) return true;
        if (tell) Notices.bad(player, Component.translatable("message.jasm.deck.dimension_upgrade"));
        return false;
    }
}
