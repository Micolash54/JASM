package dev.micolash.jasm.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Item counts of one wafer. Keys must implement equals/hashCode over item and all components. */
public final class WaferContents<K> {
    private final Map<K, Long> counts = new LinkedHashMap<>();
    private long total;

    public long total() {
        return total;
    }

    public long count(K key) {
        return counts.getOrDefault(key, 0L);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public Map<K, Long> view() {
        return Collections.unmodifiableMap(counts);
    }

    /** Accepts as much of {@code amount} as fits within {@code capacity}. Returns the accepted amount. */
    public long insert(K key, long amount, long capacity, boolean simulate) {
        requireNonNegative(amount);
        long accepted = Math.min(amount, Math.max(0, capacity - total));
        if (!simulate && accepted > 0) {
            counts.merge(key, accepted, Long::sum);
            total += accepted;
        }
        return accepted;
    }

    /** Removes up to {@code amount} of {@code key}. Returns the removed amount. */
    public long extract(K key, long amount, boolean simulate) {
        requireNonNegative(amount);
        long present = count(key);
        long taken = Math.min(amount, present);
        if (!simulate && taken > 0) {
            if (taken == present) {
                counts.remove(key);
            } else {
                counts.put(key, present - taken);
            }
            total -= taken;
        }
        return taken;
    }

    /** Loader entry point; ignores capacity. */
    public void putLoaded(K key, long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        counts.merge(key, count, Long::sum);
        total += count;
    }

    private static void requireNonNegative(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be >= 0: " + amount);
        }
    }
}
