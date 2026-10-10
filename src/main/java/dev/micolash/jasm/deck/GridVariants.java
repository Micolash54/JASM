package dev.micolash.jasm.deck;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

// a slot asking for a plain item also takes it with extra data (crop stats on harvested seeds, say), like vanilla
// crafting does. only used when there's no plain copy, and then the version there is most of
public final class GridVariants {
    private GridVariants() {}

    public static @Nullable ItemResource best(List<ItemResource> options, Map<ItemResource, Long> present) {
        Set<Item> plain = new HashSet<>();
        for (ItemResource option : options) {
            if (!option.isEmpty() && option.isComponentsPatchEmpty()) {
                plain.add(option.getItem());
            }
        }
        if (plain.isEmpty()) {
            return null;
        }
        ItemResource best = null;
        long bestCount = 0;
        for (Map.Entry<ItemResource, Long> entry : present.entrySet()) {
            ItemResource key = entry.getKey();
            if (entry.getValue() > bestCount && !key.isComponentsPatchEmpty() && plain.contains(key.getItem())
                    && DeckMenu.allowedInGrid(key.toStack(1))) {
                best = key;
                bestCount = entry.getValue();
            }
        }
        return best;
    }
}
