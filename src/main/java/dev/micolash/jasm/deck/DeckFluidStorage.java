package dev.micolash.jasm.deck;

import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.TypeRules;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * What a Deck does with the fluids on its fluid wafers: the same routing, charge and wafer checks as for items,
 * counted in millibuckets. An item wafer is never touched here, and a fluid wafer is never touched by the item paths.
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

    /** Every fluid on the Deck's usable wafers, added up. Assumes the wafers were checked this tick. */
    public static Map<FluidResource, Long> contents(WaferStore store, ItemStack deck) {
        Map<FluidResource, Long> total = new LinkedHashMap<>();
        for (WaferRecord record : DeckStorage.records(store, deck)) {
            if (record != null) {
                record.fluids().forEach((key, amount) -> total.merge(key, amount, Long::sum));
            }
        }
        return total;
    }

    /** Millibuckets of {@code key} across the Deck's usable wafers. Assumes the wafers were checked this tick. */
    public static long count(WaferStore store, ItemStack deck, FluidResource key) {
        long total = 0;
        for (WaferRecord record : DeckStorage.records(store, deck)) {
            if (record != null) {
                total += record.countFluid(key);
            }
        }
        return total;
    }

    /** How many millibuckets of {@code key}, up to {@code amount}, the Deck's fluid wafers would take. Changes nothing. */
    public static long room(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player,
            DeckStorage.@Nullable Checked checked) {
        if (!DeckStorage.checkDimension(deck, player, false)) return 0;
        if (amount <= 0 || !WaferEligibility.checkFluid(key, player.level().registryAccess()).accepted()) {
            return 0;
        }
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

    /** Takes up to {@code amount} millibuckets of {@code key} out, as far as the charge pays for. Returns the amount taken. */
    public static long withdraw(WaferStore store, ItemStack deck, FluidResource key, long amount, ServerPlayer player, boolean tell,
            DeckStorage.@Nullable Checked checked) {
        if (!DeckStorage.checkDimension(deck, player, tell)) return 0;
        if (amount <= 0 || key.isEmpty()) {
            return 0;
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

    private static List<FluidView> views(WaferStore store, ItemStack deck, ServerPlayer player, DeckStorage.@Nullable Checked checked) {
        return DeckStorage.views(store, deck, player, checked).stream().map(FluidView::new).toList();
    }
}
