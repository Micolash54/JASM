package dev.micolash.jasm.wafer;

import java.util.Map;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What a Type Wafer counts as one type: whatever would stack together in vanilla. Stored variants already keep every
 * difference in data apart; on top of that, items that stack to 1 (tools, armour) are one type per item, since two of
 * them never stack even when identical.
 */
public final class TypeRules {
    private TypeRules() {}

    public static boolean isSingle(ItemResource item) {
        return item.getMaxStackSize() <= 1;
    }

    /** Types taken by these contents. Each entry from a removed mod counts as one type. */
    public static long typesUsed(Map<ItemResource, Long> contents, int unreadableEntries) {
        long used = unreadableEntries;
        for (Map.Entry<ItemResource, Long> entry : contents.entrySet()) {
            used += isSingle(entry.getKey()) ? entry.getValue() : 1;
        }
        return used;
    }

    /**
     * How many of {@code item} still fit, going by types alone: a variant already stored can grow to the per-type
     * limit, a new one needs a free type, and items that stack to 1 take a type each.
     */
    public static long room(ItemResource item, long stored, long typesUsed, int types, int perType) {
        long freeTypes = Math.max(0, types - typesUsed);
        if (isSingle(item)) {
            return freeTypes;
        }
        if (stored > 0) {
            return Math.max(0, perType - stored);
        }
        return freeTypes > 0 ? perType : 0;
    }
}
