package dev.micolash.jasm.core;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Filtering, sorting and count labels for the Deck grid. Pure, so it can be tested without the game. */
public final class GridEntries {
    public enum Sort {
        NAME,
        AMOUNT
    }

    /**
     * One grid entry: whatever the screen draws ({@code key}), with the text used to search and sort it. {@code weight}
     * is what "sort by amount" compares: the count, or for a fluid the buckets, so a bucket weighs the same as one item.
     */
    public record Entry<K>(K key, String name, String modId, long count, long weight) {
        public Entry(K key, String name, String modId, long count) {
            this(key, name, modId, count, count);
        }
    }

    /** Which kinds the grid lists. */
    public enum Kinds {
        ALL,
        ITEMS,
        FLUIDS;

        public Kinds next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private GridEntries() {}

    /**
     * Entries matching {@code query}, sorted. Ties are broken by name then mod id, so the order never jumps around
     * between updates.
     */
    public static <K> List<Entry<K>> view(List<Entry<K>> entries, SearchQuery query, Sort sort, boolean ascending) {
        Comparator<Entry<K>> byName = Comparator.comparing((Entry<K> e) -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::modId);
        Comparator<Entry<K>> order = switch (sort) {
            case NAME -> byName;
            case AMOUNT -> Comparator.comparingLong((Entry<K> e) -> e.weight()).thenComparing(byName);
        };
        if (!ascending) {
            order = sort == Sort.NAME ? order.reversed() : Comparator.comparingLong((Entry<K> e) -> e.weight()).reversed().thenComparing(byName);
        }
        return entries.stream().filter(e -> query.matches(e.name(), e.modId())).sorted(order).toList();
    }

    /** Weight of a fluid in "sort by amount": whole buckets, but never less than one while any is stored. */
    public static long fluidWeight(long millibuckets) {
        return millibuckets <= 0 ? 0 : Math.max(1, millibuckets / 1_000);
    }

    /** Short amount label for a fluid grid cell, in buckets: 0.5, 12.5, 123, 1.2K ... */
    public static String abbreviateBuckets(long millibuckets) {
        if (millibuckets < 10_000) {
            long tenths = millibuckets / 100;
            return tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
        }
        return abbreviate(millibuckets / 1_000);
    }

    /** Short count label for a grid slot: 999, 1.2K, 12K, 123K, 1.2M, 12M, 1.2B ... */
    public static String abbreviate(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        String[] units = {"K", "M", "B", "T"};
        double value = count;
        int unit = -1;
        while (value >= 1_000 && unit < units.length - 1) {
            value /= 1_000;
            unit++;
        }
        if (value < 10) {
            double floored = Math.floor(value * 10) / 10;
            return (floored == Math.floor(floored) ? Long.toString((long) floored) : Double.toString(floored)) + units[unit];
        }
        return (long) Math.floor(value) + units[unit];
    }
}
