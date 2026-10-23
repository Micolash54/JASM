package dev.micolash.jasm.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The most of an item that can be made from what storage holds now: plans bigger and bigger amounts on one copy of
 * storage, then halves the gap between the last that worked and the first that didn't. Every plan is the real
 * planner's, so recipe alternatives and items copied from themselves count the same as in a request.
 */
public final class MaxCraft {
    private MaxCraft() {}

    /**
     * The most of {@code target} that plans without a problem and needs no more than {@code room} in a server, up to
     * {@code cap}; 0 when not even one can be made. Amounts are counted in lots of {@code unit} planner units (1000 for
     * a fluid asked for in buckets). The answer is rounded up to whole crafts as far as the cap allows. Once
     * {@code budget} nanoseconds of {@code clock} have passed, the best amount found so far is the answer.
     */
    public static <K> long most(K target, long cap, long unit, Map<K, Long> stock, CraftPlanner.Book<K> book, long room, long budget,
            LongSupplier clock) {
        if (cap < 1 || unit < 1 || book.patternsFor(target).isEmpty()) {
            return 0;
        }
        long deadline = clock.getAsLong() + budget;
        Search<K> search = new Search<>(target, unit, relevant(target, stock, book), book, room);
        if (!search.fits(1)) {
            return 0;
        }
        long good = Math.min(cap, search.made);
        long bad = -1;
        // Doubling first: a small answer is found in a few plans, a big one in about twice as many as halving needs.
        while (bad < 0 && good < cap && clock.getAsLong() < deadline) {
            long probe = good >= cap / 2 ? cap : good * 2;
            if (search.fits(probe)) {
                good = Math.min(cap, Math.max(probe, search.made));
            } else {
                bad = probe;
            }
        }
        while (bad > good + 1 && clock.getAsLong() < deadline) {
            long middle = good + (bad - good) / 2;
            if (search.fits(middle)) {
                good = Math.min(cap, Math.max(middle, search.made));
            } else {
                bad = middle;
            }
        }
        return good;
    }

    /**
     * Only what the target can be made from: everything any of its patterns, and theirs in turn, could take. Planning
     * looks at nothing else, and copying a big storage for every plan would cost more than the plan.
     */
    private static <K> Map<K, Long> relevant(K target, Map<K, Long> stock, CraftPlanner.Book<K> book) {
        Set<K> seen = new HashSet<>();
        Deque<K> next = new ArrayDeque<>();
        seen.add(target);
        next.add(target);
        while (!next.isEmpty()) {
            for (CraftPlanner.Pattern<K> pattern : book.patternsFor(next.poll())) {
                for (List<K> slot : pattern.slots()) {
                    for (K option : slot) {
                        if (seen.add(option)) {
                            next.add(option);
                        }
                    }
                }
            }
        }
        Map<K, Long> kept = new HashMap<>();
        for (K key : seen) {
            Long count = stock.get(key);
            if (count != null && count > 0) {
                kept.put(key, count);
            }
        }
        return kept;
    }

    private static final class Search<K> {
        private final K target;
        private final long unit;
        private final Map<K, Long> stock;
        private final CraftPlanner.Book<K> book;
        private final long room;
        /** What the last plan that worked makes, in lots: at least what was asked, more when a craft makes several. */
        long made;

        Search(K target, long unit, Map<K, Long> stock, CraftPlanner.Book<K> book, long room) {
            this.target = target;
            this.unit = unit;
            this.stock = stock;
            this.book = book;
            this.room = room;
        }

        boolean fits(long amount) {
            long units = amount > Long.MAX_VALUE / unit ? Long.MAX_VALUE : amount * unit;
            CraftPlanner.Plan<K> plan = CraftPlanner.plan(target, units, stock, book);
            if (!plan.ok() || plan.size() > room) {
                return false;
            }
            made = Math.max(amount, plan.made() / unit);
            return true;
        }
    }
}
