package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.crafting.ItemVariants;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

// a slot asking for a plain item also takes it with extra data (crop stats on harvested seeds, say), like vanilla
// crafting does, as far as ItemVariants allows, and a JASM item carrying JASM's own data (charge, training, settings).
// only used when there's no plain copy, and then the version there is most of (a critter: the least trained, then the
// least charged)
public final class GridVariants {
    private GridVariants() {}

    /** Never a wafer: those go through {@link #emptyWafer}. */
    public static @Nullable ItemResource best(List<ItemResource> options, Map<ItemResource, Long> present) {
        return best(options, present, key -> true);
    }

    /** As above, looking only at copies {@code usable} passes. */
    public static @Nullable ItemResource best(List<ItemResource> options, Map<ItemResource, Long> present, Predicate<ItemResource> usable) {
        Set<Item> plain = plainItems(options);
        if (plain.isEmpty()) {
            return null;
        }
        ItemResource best = null;
        long bestCount = 0;
        for (Map.Entry<ItemResource, Long> entry : present.entrySet()) {
            ItemResource key = entry.getKey();
            if (entry.getValue() > 0 && (best == null || better(key, entry.getValue(), best, bestCount)) && !key.isComponentsPatchEmpty()
                    && plain.contains(key.getItem()) && !(key.getItem() instanceof WaferItem) && DeckMenu.allowedInGrid(key.toStack(1))
                    && (ItemVariants.standsIn(ItemResource.of(key.getItem()), key) || jasmData(key)) && usable.test(key)) {
                best = key;
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    // critters go least trained, then least charged, first; anything else the version there is most of
    private static boolean better(ItemResource key, long count, ItemResource best, long bestCount) {
        if (key.getItem() instanceof BitlingItem && best.getItem() instanceof BitlingItem) {
            return BitlingItem.useOrder(key.toStack(1)) < BitlingItem.useOrder(best.toStack(1));
        }
        return count > bestCount;
    }

    /**
     * A JASM item carrying only what JASM keeps on it (charge, training, owner, name, settings): a block picked up, a
     * Bitling that has worked, a thin port taken off a cable. A fill may use it for a slot asking for that item, which the
     * shared rule leaves out so automatic crafting never eats one. Anything else on it, enchantments or contents say,
     * still keeps it out; an empty contents mark (thin ports carry one) holds nothing and doesn't.
     */
    static boolean jasmData(ItemResource key) {
        Identifier id = BuiltInRegistries.ITEM.getKey(key.getItem());
        if (!Jasm.MODID.equals(id.getNamespace())) {
            return false;
        }
        ItemStack rest = key.toStack(1);
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : key.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            Identifier typeId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (type == DataComponents.CUSTOM_NAME || typeId != null && Jasm.MODID.equals(typeId.getNamespace())
                    || type == DataComponents.CONTAINER && ItemContainerContents.EMPTY.equals(entry.getValue().orElse(null))) {
                rest.remove(type);
            }
        }
        return !ItemVariants.isProtected(rest);
    }

    /**
     * For a fill of {@code recipe}: whether the recipe still matches with a copy carrying extra data in a grid slot and
     * the first item of every other slot there. Anything goes when there is no recipe, or those first items don't match
     * it anyway (nothing to judge by).
     */
    public static BiPredicate<Integer, ItemResource> recipeTakes(@Nullable CraftingRecipe recipe, List<List<ItemResource>> wanted, Level level) {
        if (recipe == null || wanted.size() > 9) {
            return (slot, key) -> true;
        }
        List<ItemStack> base = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            base.add(i < wanted.size() && !wanted.get(i).isEmpty() ? wanted.get(i).getFirst().toStack(1) : ItemStack.EMPTY);
        }
        if (!recipe.matches(CraftingInput.of(3, 3, base), level)) {
            return (slot, key) -> true;
        }
        return (slot, key) -> {
            List<ItemStack> test = new ArrayList<>(base);
            test.set(slot, key.toStack(1));
            return recipe.matches(CraftingInput.of(3, 3, test), level);
        };
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
