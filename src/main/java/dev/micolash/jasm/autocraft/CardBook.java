package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.CraftPlanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The cards a network knows, as the planner sees them. Each slot of a card accepts its encoded item and any other
 * item that is stored or can be crafted, as long as the recipe still matches with it there.
 */
public final class CardBook implements CraftPlanner.Book<ItemResource> {
    private final Map<ItemResource, List<CraftPlanner.Pattern<ItemResource>>> byOutput = new LinkedHashMap<>();
    private final Map<RecipeCard, CardPattern> byCard = new HashMap<>();

    /** {@code cards} as found on the network; {@code candidates} are the items worth trying in a slot. */
    public CardBook(ServerLevel level, Collection<RecipeCard> cards, Collection<ItemResource> stored) {
        List<CardRecipes.Resolved> resolved = new ArrayList<>();
        for (RecipeCard card : cards) {
            CardRecipes.Resolved r = CardRecipes.resolve(level, card);
            if (r != null && !byCard.containsKey(card)) {
                resolved.add(r);
                byCard.put(card, null);
            }
        }
        Set<ItemResource> candidates = new LinkedHashSet<>(stored);
        resolved.forEach(r -> candidates.add(r.outputKey()));
        for (CardRecipes.Resolved r : resolved) {
            CardPattern pattern = new CardPattern(level, r, candidates);
            byCard.put(r.card(), pattern);
            byOutput.computeIfAbsent(r.outputKey(), k -> new ArrayList<>()).add(pattern);
        }
    }

    @Override
    public List<CraftPlanner.Pattern<ItemResource>> patternsFor(ItemResource key) {
        return byOutput.getOrDefault(key, List.of());
    }

    /** Every item some card makes. */
    public Set<ItemResource> outputs() {
        return byOutput.keySet();
    }

    /** One card as a planner pattern. */
    public static final class CardPattern implements CraftPlanner.Pattern<ItemResource> {
        private final CardRecipes.Resolved card;
        private final List<List<ItemResource>> slots = new ArrayList<>();
        private final Map<ItemResource, Long> remainders = new LinkedHashMap<>();

        CardPattern(ServerLevel level, CardRecipes.Resolved card, Collection<ItemResource> candidates) {
            this.card = card;
            // Only items some ingredient of the recipe names are tried; recipes that name none try them all.
            Set<Item> named = new java.util.HashSet<>();
            PlacementInfo placement = card.recipe().placementInfo();
            if (!placement.isImpossibleToPlace()) {
                for (Ingredient ingredient : placement.ingredients()) {
                    ingredient.items().map(Holder::value).forEach(named::add);
                }
            }
            for (int slot = 0; slot < 9; slot++) {
                ItemStack encoded = card.encoded(slot);
                if (encoded.isEmpty()) {
                    continue;
                }
                List<ItemResource> options = new ArrayList<>();
                options.add(ItemResource.of(encoded));
                for (ItemResource candidate : candidates) {
                    if (!options.contains(candidate) && (named.isEmpty() || named.contains(candidate.getItem()))
                            && card.accepts(level, slot, candidate)) {
                        options.add(candidate);
                    }
                }
                slots.add(List.copyOf(options));
            }
            NonNullList<ItemStack> inputs = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < 9; i++) {
                inputs.set(i, card.encoded(i));
            }
            for (ItemStack left : card.recipe().getRemainingItems(CraftingInput.of(3, 3, inputs))) {
                if (!left.isEmpty()) {
                    remainders.merge(ItemResource.of(left), (long) left.getCount(), Long::sum);
                }
            }
        }

        public CardRecipes.Resolved card() {
            return card;
        }

        @Override
        public ItemResource output() {
            return card.outputKey();
        }

        @Override
        public long outputCount() {
            return card.outputCount();
        }

        @Override
        public List<List<ItemResource>> slots() {
            return slots;
        }

        @Override
        public Map<ItemResource, Long> remainders() {
            return remainders;
        }
    }
}
