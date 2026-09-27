package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.CraftPlanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
 * The cards a network knows, as the planner sees them. Each slot of a crafting card accepts its encoded item and any
 * other item that is stored or can be crafted, as long as the recipe still matches with it there. A processing card
 * takes exactly the items written on it, and only counts while one of its machines can be reached.
 */
public final class CardBook implements CraftPlanner.Book<ItemResource> {
    /** A pattern made from a card. */
    public interface Entry extends CraftPlanner.Pattern<ItemResource> {
        Card card();
    }

    private final Map<ItemResource, List<CraftPlanner.Pattern<ItemResource>>> byOutput = new LinkedHashMap<>();
    /** What processing cards whose machines can't be reached would make. */
    private final Set<ItemResource> unreachable = new HashSet<>();

    /**
     * {@code cards} as found on the network; {@code stored} are the items worth trying in a crafting slot;
     * {@code reachable} says whether a processing card has a machine it can use.
     */
    public CardBook(ServerLevel level, Collection<? extends Card> cards, Collection<ItemResource> stored, Predicate<ProcessingCard> reachable) {
        List<CardRecipes.Resolved> resolved = new ArrayList<>();
        List<ProcessingCard> processing = new ArrayList<>();
        Set<Card> seen = new HashSet<>();
        for (Card card : cards) {
            if (!seen.add(card)) {
                continue;
            }
            if (card instanceof RecipeCard crafting) {
                CardRecipes.Resolved r = CardRecipes.resolve(level, crafting);
                if (r != null) {
                    resolved.add(r);
                }
            } else if (card instanceof ProcessingCard p && !p.main().isEmpty() && !p.usedInputs().isEmpty()) {
                if (reachable.test(p)) {
                    processing.add(p);
                } else {
                    unreachable.add(p.main().item());
                }
            }
        }
        Set<ItemResource> candidates = new LinkedHashSet<>(stored);
        resolved.forEach(r -> candidates.add(r.outputKey()));
        processing.forEach(p -> candidates.add(p.main().item()));
        for (CardRecipes.Resolved r : resolved) {
            add(new CardPattern(level, r, candidates));
        }
        for (ProcessingCard p : processing) {
            add(new ProcessingPattern(p));
        }
        unreachable.removeAll(byOutput.keySet());
    }

    /** A book of crafting cards only. */
    public CardBook(ServerLevel level, Collection<? extends Card> cards, Collection<ItemResource> stored) {
        this(level, cards, stored, p -> true);
    }

    private void add(Entry pattern) {
        byOutput.computeIfAbsent(pattern.output(), k -> new ArrayList<>()).add(pattern);
    }

    @Override
    public List<CraftPlanner.Pattern<ItemResource>> patternsFor(ItemResource key) {
        return byOutput.getOrDefault(key, List.of());
    }

    /** Every item some card makes. */
    public Set<ItemResource> outputs() {
        return byOutput.keySet();
    }

    /** Whether {@code key} is made only by processing cards none of whose machines can be reached. */
    public boolean unreachable(ItemResource key) {
        return unreachable.contains(key);
    }

    /** One crafting card as a planner pattern. */
    public static final class CardPattern implements Entry {
        private final CardRecipes.Resolved card;
        private final List<List<ItemResource>> slots = new ArrayList<>();
        private final Map<ItemResource, Long> remainders = new LinkedHashMap<>();

        CardPattern(ServerLevel level, CardRecipes.Resolved card, Collection<ItemResource> candidates) {
            this.card = card;
            // Only items some ingredient of the recipe names are tried; recipes that name none try them all.
            Set<Item> named = new HashSet<>();
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

        public CardRecipes.Resolved resolved() {
            return card;
        }

        @Override
        public Card card() {
            return card.card();
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

    /** One processing card as a planner pattern: its first output is what it makes, the others come along. */
    public static final class ProcessingPattern implements Entry {
        private final ProcessingCard card;
        private final List<List<ItemResource>> slots = new ArrayList<>();
        private final List<Long> amounts = new ArrayList<>();
        private final Map<ItemResource, Long> extras = new LinkedHashMap<>();

        ProcessingPattern(ProcessingCard card) {
            this.card = card;
            for (ProcessingCard.Amount input : card.usedInputs()) {
                slots.add(List.of(input.item()));
                amounts.add((long) input.count());
            }
            for (ProcessingCard.Amount extra : card.extras()) {
                extras.merge(extra.item(), (long) extra.count(), Long::sum);
            }
        }

        @Override
        public Card card() {
            return card;
        }

        @Override
        public ItemResource output() {
            return card.main().item();
        }

        @Override
        public long outputCount() {
            return card.main().count();
        }

        @Override
        public List<List<ItemResource>> slots() {
            return slots;
        }

        @Override
        public long amount(int slot) {
            return amounts.get(slot);
        }

        @Override
        public Map<ItemResource, Long> remainders() {
            return extras;
        }
    }
}
