package dev.micolash.jasm.deck;

import dev.micolash.jasm.core.DepositRouter;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.WaferValidator;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Moves everything on one of a Deck's wafers onto its other wafers, by the normal deposit order and filters. It never
 * voids and never reaches the network's storage blocks. What finds no room, or no charge, stays where it was.
 */
public final class WaferEmptying {
    public enum Outcome { DONE, NO_ROOM, NO_CHARGE, NOTHING }

    /** The bar moves in at most this many steps, one a tick, so even a full wafer is done in about a second. */
    public static final int STEPS = 20;

    /** What there is to move: items, or millibuckets on a fluid wafer. */
    public record Plan(long total, boolean fluid) {}

    /** {@code moved} and {@code left} are items, or millibuckets on a fluid wafer. */
    public record Result(long moved, long left, Outcome outcome, boolean fluid) {
        public static final Result NOTHING = new Result(0, 0, Outcome.NOTHING, false);
        public static final StreamCodec<ByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Result::moved,
                ByteBufCodecs.VAR_LONG, Result::left,
                ByteBufCodecs.idMapper(i -> Outcome.values()[i], Enum::ordinal), Result::outcome,
                ByteBufCodecs.BOOL, Result::fluid,
                Result::new);
    }

    private WaferEmptying() {}

    public static Result empty(WaferStore store, ItemStack deck, int slot, ServerPlayer player) {
        return empty(store, deck, slot, player, Long.MAX_VALUE);
    }

    /** What emptying this wafer would move, or null if there is nothing to move or the Deck can't work here. */
    public static @Nullable Plan plan(WaferStore store, ItemStack deck, int slot, ServerPlayer player) {
        if (!DeckItem.worksIn(deck, player.level()) || !DeckStorage.checkDimension(deck, player, false)) {
            return null;
        }
        List<DeckStorage.SlotView> views = DeckStorage.views(store, deck, player, null);
        if (slot < 0 || slot >= views.size() || views.get(slot).record() == null) {
            return null;
        }
        WaferRecord source = views.get(slot).record();
        long total = readable(source);
        return total > 0 ? new Plan(total, source.isFluid()) : null;
    }

    private static long readable(WaferRecord source) {
        return (source.isFluid() ? source.fluids() : source.contents()).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Moves up to {@code limit} items (millibuckets on a fluid wafer) and reports what that step did. */
    public static Result empty(WaferStore store, ItemStack deck, int slot, ServerPlayer player, long limit) {
        if (!DeckItem.worksIn(deck, player.level()) || !DeckStorage.checkDimension(deck, player, false)) {
            return Result.NOTHING;
        }
        List<DeckStorage.SlotView> views = DeckStorage.views(store, deck, player, null);
        if (slot < 0 || slot >= views.size() || views.get(slot).record() == null) {
            return Result.NOTHING;
        }
        WaferRecord source = views.get(slot).record();
        // The wafer being emptied takes nothing back.
        List<DeckStorage.SlotView> targets = new ArrayList<>(views);
        targets.set(slot, new DeckStorage.SlotView(ItemStack.EMPTY, null));
        boolean fluid = source.isFluid();
        long moved = fluid ? moveFluids(store, deck, source, targets, player, limit) : moveItems(store, deck, source, targets, player, limit);
        // Readable contents only: entries from removed mods can't be moved and aren't counted as left over.
        long left = readable(source);
        boolean outOfCharge = fluid ? DeckStorage.affordableFluid(deck) <= 0 : DeckStorage.affordable(deck) <= 0;
        Outcome outcome = left == 0 ? Outcome.DONE : outOfCharge ? Outcome.NO_CHARGE : Outcome.NO_ROOM;
        return new Result(moved, left, moved == 0 && left == 0 ? Outcome.NOTHING : outcome, fluid);
    }

    private static long moveItems(WaferStore store, ItemStack deck, WaferRecord source, List<DeckStorage.SlotView> targets, ServerPlayer player, long limit) {
        DeckWafers wafers = DeckItem.wafers(deck);
        long budget = Math.min(limit, DeckStorage.affordable(deck));
        long moved = 0;
        for (Map.Entry<ItemResource, Long> entry : new LinkedHashMap<>(source.contents()).entrySet()) {
            if (budget <= 0) break;
            ItemResource key = entry.getKey();
            for (DepositRouter.Allocation allocation : DepositRouter.planDeposit(targets, key, Math.min(entry.getValue(), budget))) {
                WaferRecord target = targets.get(allocation.slot()).record();
                if (target == null) {
                    ItemStack blank = targets.get(allocation.slot()).wafer();
                    target = WaferValidator.format(store, blank, player);
                    wafers = wafers.with(allocation.slot(), blank);
                    targets.set(allocation.slot(), new DeckStorage.SlotView(blank, target));
                }
                long taken = store.extract(source, key, allocation.amount(), false, player);
                long put = store.insert(target, key, taken, false, player);
                if (put < taken) {
                    store.insert(source, key, taken - put, false, player);
                }
                moved += put;
                budget -= put;
            }
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        DeckStorage.pay(deck, moved);
        return moved;
    }

    private static long moveFluids(WaferStore store, ItemStack deck, WaferRecord source, List<DeckStorage.SlotView> targets, ServerPlayer player, long limit) {
        DeckWafers wafers = DeckItem.wafers(deck);
        long budget = Math.min(limit, DeckStorage.affordableFluid(deck));
        long moved = 0;
        for (Map.Entry<FluidResource, Long> entry : new LinkedHashMap<>(source.fluids()).entrySet()) {
            if (budget <= 0) break;
            FluidResource key = entry.getKey();
            for (DepositRouter.Allocation allocation : DepositRouter.planDeposit(DeckFluidStorage.fluidSlots(targets), key,
                    Math.min(entry.getValue(), budget))) {
                WaferRecord target = targets.get(allocation.slot()).record();
                if (target == null) {
                    ItemStack blank = targets.get(allocation.slot()).wafer();
                    target = WaferValidator.format(store, blank, player);
                    wafers = wafers.with(allocation.slot(), blank);
                    targets.set(allocation.slot(), new DeckStorage.SlotView(blank, target));
                }
                long taken = store.extractFluid(source, key, allocation.amount(), false, player);
                long put = store.insertFluid(target, key, taken, false, player);
                if (put < taken) {
                    store.insertFluid(source, key, taken - put, false, player);
                }
                moved += put;
                budget -= put;
            }
        }
        deck.set(JasmComponents.DECK_WAFERS.get(), wafers);
        DeckStorage.pay(deck, FluidAmounts.shares(moved));
        return moved;
    }
}
