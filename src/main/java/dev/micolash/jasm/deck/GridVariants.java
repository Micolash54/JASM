package dev.micolash.jasm.deck;

import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

// a slot asking for a plain item also takes it with extra data (crop stats on harvested seeds, say), like vanilla
// crafting does. only used when there's no plain copy, and then the version there is most of
public final class GridVariants {
    private GridVariants() {}

    /** Never a wafer: those go through {@link #emptyWafer}. */
    public static @Nullable ItemResource best(List<ItemResource> options, Map<ItemResource, Long> present) {
        Set<Item> plain = plainItems(options);
        if (plain.isEmpty()) {
            return null;
        }
        ItemResource best = null;
        long bestCount = 0;
        for (Map.Entry<ItemResource, Long> entry : present.entrySet()) {
            ItemResource key = entry.getKey();
            if (entry.getValue() > bestCount && !key.isComponentsPatchEmpty() && plain.contains(key.getItem())
                    && !(key.getItem() instanceof WaferItem) && DeckMenu.allowedInGrid(key.toStack(1))) {
                best = key;
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /**
     * For a slot asking for a blank wafer: any wafer of that kind that passes {@code empty}, whatever its filter rows or
     * name. A used wafer is one of a kind, so {@code taken} says how many of each a slot before already has.
     */
    public static @Nullable ItemResource emptyWafer(List<ItemResource> options, Map<ItemResource, Long> present,
            Map<ItemResource, Long> taken, Predicate<ItemResource> empty) {
        Set<Item> plain = plainItems(options);
        plain.removeIf(item -> !(item instanceof WaferItem));
        if (plain.isEmpty()) {
            return null;
        }
        for (Map.Entry<ItemResource, Long> entry : present.entrySet()) {
            ItemResource key = entry.getKey();
            if (entry.getValue() - taken.getOrDefault(key, 0L) > 0 && !key.isComponentsPatchEmpty() && plain.contains(key.getItem())
                    && empty.test(key)) {
                return key;
            }
        }
        return null;
    }

    /** A critter drawn with a training bar, as the evolve recipes show it: the slot wants one with a full bar. */
    public static boolean wantsTrained(ItemResource option) {
        return option.getItem() instanceof BitlingItem critter && critter.trainingRequired() > 0
                && option.toStack(1).has(JasmComponents.TRAINING.get());
    }

    /**
     * {@code options} with each wanted trained critter swapped for the copies in {@code present} whose bar is full,
     * whatever else is on them (charge, name). Nothing is left for it when only untrained ones are there.
     */
    public static List<ItemResource> withTrained(List<ItemResource> options, Map<ItemResource, Long> present) {
        if (options.stream().noneMatch(GridVariants::wantsTrained)) {
            return options;
        }
        List<ItemResource> result = new ArrayList<>();
        for (ItemResource option : options) {
            if (!wantsTrained(option)) {
                result.add(option);
                continue;
            }
            BitlingItem critter = (BitlingItem) option.getItem();
            for (Map.Entry<ItemResource, Long> entry : present.entrySet()) {
                ItemResource key = entry.getKey();
                if (entry.getValue() > 0 && key.getItem() == critter && !result.contains(key)
                        && Training.full(BitlingItem.trained(key.toStack(1)), critter.trainingRequired())) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    public static boolean isWafer(ItemResource key) {
        return key.getItem() instanceof WaferItem;
    }

    private static Set<Item> plainItems(List<ItemResource> options) {
        Set<Item> plain = new HashSet<>();
        for (ItemResource option : options) {
            if (!option.isEmpty() && option.isComponentsPatchEmpty()) {
                plain.add(option.getItem());
            }
        }
        return plain;
    }
}
