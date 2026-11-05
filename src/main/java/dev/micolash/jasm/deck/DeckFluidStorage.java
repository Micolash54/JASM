package dev.micolash.jasm.deck;

import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.core.PoolRouter;
import dev.micolash.jasm.core.ResourceKeys;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.pool.PoolStore;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.InfiniteWaferItem;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.micolash.jasm.network.NetworkNode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What a Deck does with the fluids on its fluid wafers: the same routing, charge and wafer checks as for items,
 * counted in millibuckets. An item wafer is never touched here, and a fluid wafer is never touched by the item paths.
 *
 * <p>An infinite wafer comes before everything for its own fluid: the Deck counts exactly its fixed amount, takes
 * from it without end and deletes what is put in, at the usual charge. Normal wafers and tanks holding that fluid are
 * left alone and hidden behind the fixed amount.
 */
public final class DeckFluidStorage {
    /** One wafer slot as a place to put fluid. A blank fluid wafer is usable and gets set up on its first deposit. */
    private record FluidView(DeckStorage.SlotView view) implements DepositRouter.Slot<FluidResource> {
        @Override
        public boolean usable() {
            return view.usable();
        }

        @Override
        public long count(FluidResource key) {
            return view.record() == null ? 0 : view.record().countFluid(key);
        }

        @Override
        public boolean accepts(FluidResource key) {
            return view.record() == null || view.record().settings().rank(key.getFluid()) >= 0;
        }

        @Override
        public long room(FluidResource key) {
            if (view.record() != null) {
                return view.record().roomForFluid(key);
            }
            if (!view.isBlank()) {
                return 0;
            }
            WaferTier tier = ((WaferItem) view.wafer().getItem()).tier();
            if (!tier.isFluid()) {
                return 0;
            }
            return tier.isTyped() ? Math.min(tier.capacityAmount(), TypeRules.fluidRoom(0, 0, tier.types(), tier.perTypeAmount()))
                    : tier.capacityAmount();
        }
    }

    private DeckFluidStorage() {}

    /** A fluid wafer slot or a tank, for ordering when the network has storage blocks. */
    private record Unit(int index, @Nullable FluidView slot, @Nullable PoolStore port) implements PoolRouter.Unit<FluidResource> {
        @Override
        public int priority() { return port == null ? 0 : port.priority(); }
        @Override
        public boolean wafer() { return port == null; }
        @Override
        public boolean prefers(FluidResource key) { return port == null ? slot.count(key) > 0 : port.fluidUnit().prefers(key); }
        @Override
        public boolean canRead() { return port == null ? slot.usable() : port.fluidUnit().canRead(); }
        @Override
        public boolean canWrite() { return port == null ? slot.usable() : port.fluidUnit().canWrite(); }
    }

    private static List<Unit> units(List<FluidView> views, List<PoolStore> ports) {
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < views.size(); i++) units.add(new Unit(i, views.get(i), null));
        for (PoolStore port : ports) units.add(new Unit(-1, null, port));
        return units;
    }

    private static List<PoolStore> ports(ServerPlayer player, ItemStack deck, DeckStorage.@Nullable Checked checked) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        NetworkNode avoid = checked == null ? null : checked.avoid;
        return pool == null ? List.of() : pool.stores(avoid);
    }

    private static long depositCombined(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked, List<PoolStore> ports) {
        List<FluidView> views = views(store, deck, player, checked);
        DeckWafers wafers = DeckItem.wafers(deck);
        long moved = 0;
        for (Unit unit : PoolRouter.insertOrder(units(views, ports), key)) {
            long left = amount - moved;
            if (left <= 0) break;
            if (unit.port() != null) {
                moved += unit.port().insertFluidNow(key, left);
                continue;
            }
            FluidView view = unit.slot();
            long take = view.accepts(key) ? Math.min(left, view.room(key)) : 0;
            if (take <= 0) continue;
            WaferRecord record = view.view().record();
            if (record == null) {
                ItemStack blank = view.view().wafer();
                record = WaferValidator.format(store, blank, player);
                wafers = wafers.with(unit.index(), blank);
            }
            moved += store.insertFluid(record, key, take, false, player);
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        DeckStorage.pay(deck, FluidAmounts.shares(moved));
        return moved;
    }

    private static long withdrawCombined(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked, List<PoolStore> ports) {
        long taken = 0;
        for (Unit unit : PoolRouter.extractOrder(units(views(store, deck, player, checked), ports), key)) {
            long left = amount - taken;
            if (left <= 0) break;
            if (unit.port() != null) taken += unit.port().extractFluidNow(key, left);
            else if (unit.slot().view().record() != null)
                taken += store.extractFluid(unit.slot().view().record(), key, Math.min(left, unit.slot().count(key)), false, player);
        }
        DeckStorage.pay(deck, FluidAmounts.shares(taken));
        return taken;
    }

    private static long roomCombined(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked, List<PoolStore> ports, @Nullable TransactionContext tx) {
        long room = 0;
        for (Unit unit : PoolRouter.insertOrder(units(views(store, deck, player, checked), ports), key)) {
            long left = amount - room;
            if (left <= 0) break;
            room += unit.port() != null ? unit.port().roomFluid(key, left, tx)
                    : unit.slot().accepts(key) ? Math.min(left, unit.slot().room(key)) : 0;
        }
        return room;
    }

    /** Every fluid on the Deck's usable wafers, added up (wafers only). Assumes the wafers were checked this tick. */
    public static Map<FluidResource, Long> contents(WaferStore store, ItemStack deck) {
        Map<FluidResource, Long> total = ResourceKeys.newMap();
        for (WaferRecord record : DeckStorage.records(store, deck)) {
            if (record != null) {
                record.fluids().forEach((key, amount) -> total.merge(key, amount, Long::sum));
            }
        }
        showInfinite(deck, total);
        return total;
    }

    /** Sets every fluid an infinite wafer covers to the fixed amount, whatever else holds it. */
    static void showInfinite(ItemStack deck, Map<FluidResource, Long> totals) {
        for (FluidResource key : InfiniteWaferItem.in(deck)) totals.put(key, InfiniteWaferItem.AMOUNT);
    }

    /** Millibuckets of {@code key} across the Deck's usable wafers (wafers only). Assumes the wafers were checked this tick. */
    public static long count(WaferStore store, ItemStack deck, FluidResource key) {
        if (InfiniteWaferItem.covers(deck, key)) return InfiniteWaferItem.AMOUNT;
        long total = 0;
        for (WaferRecord record : DeckStorage.records(store, deck)) {
            if (record != null) {
                total += record.countFluid(key);
            }
        }
        return total;
    }

    /** Wafers and the network's storage blocks added up. Assumes the wafers were checked this tick. */
    public static long count(WaferStore store, ItemStack deck, FluidResource key, ServerPlayer player) {
        if (InfiniteWaferItem.covers(deck, key)) return InfiniteWaferItem.AMOUNT;
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return count(store, deck, key) + (pool == null ? 0 : pool.countFluid(key));
    }

    /** Every fluid on the wafers and in the network's storage blocks, as a fresh map. */
    public static Map<FluidResource, Long> contents(WaferStore store, ItemStack deck, ServerPlayer player) {
        Map<FluidResource, Long> total = contents(store, deck);
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        if (pool != null) pool.fluidContents().forEach((key, amount) -> total.merge(key, amount, Long::sum));
        showInfinite(deck, total);
        return total;
    }

    /** How many millibuckets of {@code key}, up to {@code amount}, the Deck's fluid wafers would take. Changes nothing. */
    public static long room(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked) {
        return room(store, deck, key, amount, player, checked, null);
    }

    /** As above, asked from inside the open transaction {@code tx}: the network's tanks answer as they stand in it. */
    public static long room(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked, @Nullable TransactionContext tx) {
        if (!DeckStorage.checkDimension(deck, player, false)) return 0;
        if (amount <= 0 || !WaferEligibility.checkFluid(key, player.level().registryAccess()).accepted()) {
            return 0;
        }
        // an infinite wafer takes it all, to delete it
        if (InfiniteWaferItem.covers(deck, key)) return amount;
        List<PoolStore> ports = ports(player, deck, checked);
        if (!ports.isEmpty()) return roomCombined(store, deck, key, amount, player, checked, ports, tx);
        return DepositRouter.planDeposit(views(store, deck, player, checked), key, amount).stream()
                .mapToLong(DepositRouter.Allocation::amount).sum();
    }

    /**
     * Stores up to {@code amount} millibuckets on the Deck's fluid wafers as far as the charge pays for; a blank
     * fluid wafer is set up first. Returns the amount stored.
     */
    public static long deposit(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked) {
        if (!DeckItem.worksIn(deck, player.level())) return 0;
        if (amount <= 0 || !WaferEligibility.checkFluid(key, player.level().registryAccess()).accepted()) {
            return 0;
        }
        amount = Math.min(amount, DeckStorage.affordableFluid(deck));
        if (amount <= 0) {
            return 0;
        }
        if (InfiniteWaferItem.covers(deck, key)) {
            DeckStorage.pay(deck, FluidAmounts.shares(amount));
            return amount;
        }
        List<PoolStore> ports = ports(player, deck, checked);
        if (!ports.isEmpty()) return depositCombined(store, deck, key, amount, player, checked, ports);
        List<FluidView> views = views(store, deck, player, checked);
        List<DepositRouter.Allocation> plan = DepositRouter.planDeposit(views, key, amount);
        DeckWafers wafers = DeckItem.wafers(deck);
        long moved = 0;
        for (DepositRouter.Allocation allocation : plan) {
            DeckStorage.SlotView view = views.get(allocation.slot()).view();
            WaferRecord record = view.record();
            if (record == null) {
                ItemStack blank = view.wafer();
                record = WaferValidator.format(store, blank, player);
                wafers = wafers.with(allocation.slot(), blank);
            }
            moved += store.insertFluid(record, key, allocation.amount(), false, player);
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        DeckStorage.pay(deck, FluidAmounts.shares(moved));
        return moved;
    }

    /** Whether a Void row would destroy {@code key}: the Deck works here, the fluid is allowed in, and a usable fluid wafer's deciding row is Allow with Void. */
    static boolean canVoid(WaferStore store, ItemStack deck, FluidResource key, ServerPlayer player, DeckStorage.@Nullable Checked checked) {
        if (key.isEmpty() || !DeckItem.worksIn(deck, player.level())) return false;
        if (!WaferEligibility.checkFluid(key, player.level().registryAccess()).accepted()) return false;
        for (DeckStorage.SlotView view : DeckStorage.views(store, deck, player, checked)) {
            // Rows on an item wafer are about items; that wafer never holds fluid.
            if (view.record() != null && view.record().isFluid() && view.record().settings().voidsExcess(key.getFluid())) return true;
        }
        return false;
    }

    /** Destroys {@code left} millibuckets of {@code key} when a Void row says so, as far as the charge pays. Returns how many were destroyed. */
    private static long voidLeft(WaferStore store, ItemStack deck, ServerPlayer player, DeckStorage.@Nullable Checked checked, FluidResource key,
            long left) {
        left = Math.min(left, DeckStorage.affordableFluid(deck));
        if (left <= 0 || !canVoid(store, deck, key, player, checked)) return 0;
        DeckStorage.pay(deck, FluidAmounts.shares(left));
        return left;
    }

    /** As {@link #deposit}, with a Void row destroying what no wafer has room for. */
    public static DeckStorage.Deposit depositResult(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.Excess excess, DeckStorage.@Nullable Checked checked) {
        // Deleted, not stored: undoing a move only takes back what was stored, so it never reaches into other wafers.
        if (InfiniteWaferItem.covers(deck, key)) return new DeckStorage.Deposit(0, deposit(store, deck, key, amount, player, checked));
        long stored = deposit(store, deck, key, amount, player, checked);
        long gone = excess == DeckStorage.Excess.VOID ? voidLeft(store, deck, player, checked, key, amount - stored) : 0;
        return new DeckStorage.Deposit(stored, gone);
    }

    /** Takes up to {@code amount} millibuckets of {@code key} out, as far as the charge pays for. Returns the amount taken. */
    public static long withdraw(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player, boolean tell,
            DeckStorage.@Nullable Checked checked) {
        if (!DeckStorage.checkDimension(deck, player, tell)) return 0;
        if (amount <= 0 || key.isEmpty()) {
            return 0;
        }
        if (InfiniteWaferItem.covers(deck, key)) {
            if (!DeckStorage.canAfford(deck, player, tell)) return 0;
            long taken = Math.min(amount, DeckStorage.affordableFluid(deck));
            DeckStorage.pay(deck, FluidAmounts.shares(taken));
            return taken;
        }
        List<PoolStore> ports = ports(player, deck, checked);
        if (!ports.isEmpty()) {
            if (!DeckStorage.canAfford(deck, player, tell)) return 0;
            return withdrawCombined(store, deck, key, Math.min(amount, DeckStorage.affordableFluid(deck)), player, checked, ports);
        }
        List<FluidView> views = views(store, deck, player, checked);
        List<DepositRouter.Allocation> plan = DepositRouter.planWithdraw(views, key, amount);
        if (plan.isEmpty() || !DeckStorage.canAfford(deck, player, tell)) {
            return 0;
        }
        long affordable = DeckStorage.affordableFluid(deck);
        if (affordable < plan.stream().mapToLong(DepositRouter.Allocation::amount).sum()) {
            plan = DepositRouter.planWithdraw(views, key, affordable);
        }
        long taken = 0;
        for (DepositRouter.Allocation allocation : plan) {
            taken += store.extractFluid(views.get(allocation.slot()).view().record(), key, allocation.amount(), false, player);
        }
        DeckStorage.pay(deck, FluidAmounts.shares(taken));
        return taken;
    }

    /** What a Pull all did: the millibuckets deleted, and whether the charge ran out before all of it was pulled. */
    public record Pull(long amount, boolean outOfCharge) {}

    /**
     * Pull all: the infinite wafer in {@code slot} takes its fluid off the Deck's own fluid wafers and deletes it, at the
     * normal charge for moving fluid. Short of charge, it pulls as many whole buckets as the charge pays for. Never
     * touches the network's tanks; the wafers keep their settings and name. Returns null when {@code slot} holds no
     * working infinite wafer.
     */
    public static @Nullable Pull pullIntoInfinite(WaferStore store, ItemStack deck, int slot, ServerPlayer player) {
        DeckItem item = (DeckItem) deck.getItem();
        if (slot < 0 || slot >= item.tier().slots() || !(DeckItem.wafers(deck).get(slot).getItem() instanceof InfiniteWaferItem infinite)) {
            return null;
        }
        FluidResource key = infinite.resource();
        DeckStorage.checkAll(store, deck, player);
        List<WaferRecord> records = DeckStorage.records(store, deck);
        long held = 0;
        for (WaferRecord record : records) {
            if (record != null && record.isFluid()) held += record.countFluid(key);
        }
        long affordable = DeckStorage.affordableFluid(deck);
        long budget = held <= affordable ? held : affordable / FluidAmounts.PER_BUCKET * FluidAmounts.PER_BUCKET;
        long pulled = 0;
        for (WaferRecord record : records) {
            long take = record == null || !record.isFluid() ? 0 : Math.min(record.countFluid(key), budget - pulled);
            if (take > 0) pulled += store.extractFluid(record, key, take, false, player);
        }
        DeckStorage.pay(deck, FluidAmounts.shares(pulled));
        return new Pull(pulled, pulled < held);
    }

    /** The given wafer slots as places to put fluid, for planners outside this class. */
    static List<? extends DepositRouter.Slot<FluidResource>> fluidSlots(List<DeckStorage.SlotView> views) {
        return views.stream().map(FluidView::new).toList();
    }

    private static List<FluidView> views(WaferStore store, ItemStack deck, ServerPlayer player, DeckStorage.@Nullable Checked checked) {
        return DeckStorage.views(store, deck, player, checked).stream().map(FluidView::new).toList();
    }
}
