package dev.micolash.jasm.deck;

import dev.micolash.jasm.config.JasmConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * How JEI's "+" fills a Crafting Deck's grid. The screen runs it to colour the recipe and the server runs it to place the
 * items, so what the screen shows is what lands in the grid.
 *
 * <p>Slot by slot, each takes the allowed item there is most of among what the slots before it left, so a recipe that
 * takes any planks can use two kinds. A slot asking for a plain item takes a copy with extra data when no plain copy is
 * left, the one there is most of, if the shared rule ({@code ItemVariants}) and the recipe allow it; each slot may take
 * a different copy. Items on the wafers or in the network cost charge to take out, so only as many count as the charge
 * pays for; the inventory is free. A slot with nothing left stays empty. One of each by default; with {@code max}, as
 * many full crafts as there is enough for, once there is enough for one.
 */
public final class GridFill {
    private GridFill() {}

    /** Where a fill takes items from. */
    public interface Source {
        /** On the wafers and in the network's storage blocks. */
        long stored(ItemResource key);

        /** In the inventory. */
        long carried(ItemResource key);

        /** Everything stored, each version on its own. Only read when a slot has no plain copy left. */
        Map<ItemResource, Long> allStored();

        /** Everything carried, each version on its own. */
        Map<ItemResource, Long> allCarried();

        /** How many stored items the charge pays for. */
        long budget();

        /** Whether the fill may put this in the grid at all. */
        boolean takes(ItemResource key);

        static Source of(Map<ItemResource, Long> stored, Map<ItemResource, Long> carried, long budget, Predicate<ItemResource> takes) {
            return new Source() {
                @Override
                public long stored(ItemResource key) {
                    return stored.getOrDefault(key, 0L);
                }

                @Override
                public long carried(ItemResource key) {
                    return carried.getOrDefault(key, 0L);
                }

                @Override
                public Map<ItemResource, Long> allStored() {
                    return stored;
                }

                @Override
                public Map<ItemResource, Long> allCarried() {
                    return carried;
                }

                @Override
                public long budget() {
                    return budget;
                }

                @Override
                public boolean takes(ItemResource key) {
                    return takes.test(key);
                }
            };
        }
    }

    /** One slot's items: how many come off the wafers or network, and how many out of the inventory. */
    public record Pick(ItemResource key, int stored, int carried) {
        public int count() {
            return stored + carried;
        }
    }

    /** How many stored items {@code energy} pays for. */
    public static long budget(long energy) {
        int cost = JasmConfig.DECK_ENERGY_PER_ITEM.getAsInt();
        return cost <= 0 ? Long.MAX_VALUE : Math.max(0, energy) / cost;
    }

    /** One pick per slot of {@code wanted}, in order; null where the slot stays empty. An empty option list is an empty slot. */
    public static List<@Nullable Pick> plan(List<List<ItemResource>> wanted, Source source, boolean max) {
        return plan(wanted, source, max, (slot, key) -> true);
    }

    /**
     * As above; {@code variantFits} says whether the recipe still takes a copy with extra data in a slot (see
     * {@link GridVariants#recipeTakes}).
     */
    public static List<@Nullable Pick> plan(List<List<ItemResource>> wanted, Source source, boolean max,
            BiPredicate<Integer, ItemResource> variantFits) {
        List<@Nullable Pick> picks = new Pass(source, 1, variantFits).run(wanted);
        if (!max || !full(wanted, picks)) {
            return picks;
        }
        // the largest number of full crafts the slot-by-slot picking can fill
        int low = 1;
        int high = 99;
        while (low < high) {
            int sets = (low + high + 1) >>> 1;
            List<@Nullable Pick> more = new Pass(source, sets, variantFits).run(wanted);
            if (full(wanted, more)) {
                picks = more;
                low = sets;
            } else {
                high = sets - 1;
            }
        }
        return picks;
    }

    private static boolean full(List<List<ItemResource>> wanted, List<@Nullable Pick> picks) {
        for (int i = 0; i < wanted.size(); i++) {
            if (!wanted.get(i).isEmpty() && picks.get(i) == null) {
                return false;
            }
        }
        return true;
    }

    /** Fills every slot with {@code sets} of one item, or leaves it empty. */
    private static final class Pass {
        private final Source source;
        private final int sets;
        private final BiPredicate<Integer, ItemResource> variantFits;
        private final Map<ItemResource, Long> storedTaken = new HashMap<>();
        private final Map<ItemResource, Long> carriedTaken = new HashMap<>();
        private long budget;
        // everything still to be had, built when a slot needs it and dropped after each take
        private @Nullable Map<ItemResource, Long> left;

        Pass(Source source, int sets, BiPredicate<Integer, ItemResource> variantFits) {
            this.source = source;
            this.sets = sets;
            this.variantFits = variantFits;
        }

        List<@Nullable Pick> run(List<List<ItemResource>> wanted) {
            budget = source.budget();
            List<@Nullable Pick> picks = new ArrayList<>();
            for (int slot = 0; slot < wanted.size(); slot++) {
                ItemResource key = choose(wanted.get(slot), slot);
                picks.add(key == null ? null : take(key));
            }
            return picks;
        }

        private @Nullable ItemResource choose(List<ItemResource> options, int slot) {
            if (options.isEmpty()) {
                return null;
            }
            if (options.stream().anyMatch(GridVariants::wantsTrained)) {
                // a trained critter in the recipe means any copy with a full bar
                options = GridVariants.withTrained(options, left());
            }
            ItemResource best = null;
            long bestCount = sets - 1;
            for (ItemResource option : options) {
                if (option.isEmpty() || option.getMaxStackSize() < sets || !source.takes(option)) {
                    continue;
                }
                long count = left(option, source.stored(option), source.carried(option));
                if (count > bestCount) {
                    best = option;
                    bestCount = count;
                }
            }
            if (best != null) {
                return best;
            }
            ItemResource variant = GridVariants.best(options, left(),
                    key -> key.getMaxStackSize() >= sets && source.takes(key) && variantFits.test(slot, key));
            if (variant != null && left().get(variant) >= sets) {
                return variant;
            }
            // wafers stack to one; a used one is one of a kind, so what earlier slots took is already off the list
            return sets == 1 ? GridVariants.emptyWafer(options, left(), Map.of(), source::takes) : null;
        }

        private Pick take(ItemResource key) {
            long storedLeft = Math.max(0, source.stored(key) - storedTaken.getOrDefault(key, 0L));
            int stored = (int) Math.min(Math.min(storedLeft, budget), sets);
            int carried = sets - stored;
            storedTaken.merge(key, (long) stored, Long::sum);
            carriedTaken.merge(key, (long) carried, Long::sum);
            budget -= stored;
            left = null;
            return new Pick(key, stored, carried);
        }

        private long left(ItemResource key, long stored, long carried) {
            long storedLeft = Math.max(0, stored - storedTaken.getOrDefault(key, 0L));
            long carriedLeft = Math.max(0, carried - carriedTaken.getOrDefault(key, 0L));
            return carriedLeft + Math.min(storedLeft, budget);
        }

        private Map<ItemResource, Long> left() {
            if (left == null) {
                Map<ItemResource, Long> stored = source.allStored();
                Map<ItemResource, Long> carried = source.allCarried();
                Set<ItemResource> keys = new LinkedHashSet<>(stored.keySet());
                keys.addAll(carried.keySet());
                left = new LinkedHashMap<>();
                for (ItemResource key : keys) {
                    long count = left(key, stored.getOrDefault(key, 0L), carried.getOrDefault(key, 0L));
                    if (count > 0) {
                        left.put(key, count);
                    }
                }
            }
            return left;
        }
    }
}
