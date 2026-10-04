package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The order storage is used in once chests join the wafers. Putting items in: highest priority first, then storage
 * that prefers the item, then wafers before chests. Taking items out: lowest priority first, wafers before chests.
 * Ties keep the order the list came in (wafer slot order).
 */
public final class PoolRouter {
    public interface Unit<K> {
        int priority();
        boolean wafer();
        /** Already holds the item, or lists it in its filter. */
        boolean prefers(K key);
        boolean canRead();
        boolean canWrite();
    }

    private PoolRouter() {}

    public static <K, U extends Unit<K>> List<U> insertOrder(List<U> units, K key) {
        List<U> order = new ArrayList<>();
        for (U unit : units) if (unit.canWrite()) order.add(unit);
        order.sort(Comparator.comparingInt((U unit) -> -unit.priority())
                .thenComparingInt(unit -> unit.prefers(key) ? 0 : 1)
                .thenComparingInt(unit -> unit.wafer() ? 0 : 1));
        return order;
    }

    public static <K, U extends Unit<K>> List<U> extractOrder(List<U> units, K key) {
        List<U> order = new ArrayList<>();
        for (U unit : units) if (unit.canRead()) order.add(unit);
        order.sort(Comparator.comparingInt((U unit) -> unit.priority()).thenComparingInt(unit -> unit.wafer() ? 0 : 1));
        return order;
    }
}
