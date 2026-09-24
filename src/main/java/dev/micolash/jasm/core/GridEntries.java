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

    /** One grid entry: whatever the screen draws ({@code key}), with the text used to search and sort it. */
    public record Entry<K>(K key, String name, String modId, long count) {}

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
            case AMOUNT -> Comparator.comparingLong((Entry<K> e) -> e.count()).thenComparing(byName);
        };
        if (!ascending) {
            order = sort == Sort.NAME ? order.reversed() : Comparator.comparingLong((Entry<K> e) -> e.count()).reversed().thenComparing(byName);
        }
        return entries.stream().filter(e -> query.matches(e.name(), e.modId())).sorted(order).toList();
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
