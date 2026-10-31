package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.CraftPlanner;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.wafer.FluidAmounts;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The cards a network knows, as the planner sees them. Each slot of a crafting card accepts its encoded item and any
 * other item that is stored or can be crafted, as long as the recipe still matches with it there. A processing card
 * takes the items and fluids written on it, and only counts while one of its machines can be reached; when JASM finds
 * the machines' real recipe for it, each item slot also takes whatever else that recipe takes there.
 */
public final class CardBook implements CraftPlanner.Book<GridKey> {
    /** A pattern made from a card. */
    public interface Entry extends CraftPlanner.Pattern<GridKey> {
        Card card();
    }

    private final Map<GridKey, List<CraftPlanner.Pattern<GridKey>>> byOutput = new LinkedHashMap<>();
    /** What processing cards whose machines can't be reached would make. */
    private final Set<GridKey> unreachable = new HashSet<>();

    /**
     * {@code cards} as found on the network; {@code stored} are the items worth trying in a crafting slot;
     * {@code reachable} says whether a processing card has a machine it can use.
     */
    public CardBook(ServerLevel level, Collection<? extends Card> cards, Collection<ItemResource> stored, Predicate<ProcessingCard> reachable) {
        this(level, cards, stored, reachable, null);
    }

    /** As above, on {@code network}: a machine whose port was mined and placed back there is looked at where it is now. */
    public CardBook(ServerLevel level, Collection<? extends Card> cards, Collection<ItemResource> stored, Predicate<ProcessingCard> reachable,
            @Nullable CableNetwork network) {
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
                    unreachable.add(p.main().key());
                }
            }
        }
        Set<ItemResource> candidates = new LinkedHashSet<>(stored);
        resolved.forEach(r -> candidates.add(r.outputKey()));
        processing.forEach(p -> {
            if (!p.main().isFluid() && !p.main().isMaterial()) {
                candidates.add(p.main().item());
            }
        });
        for (CardRecipes.Resolved r : resolved) {
            add(new CardPattern(level, r, candidates));
        }
        for (ProcessingCard p : processing) {
            add(new ProcessingPattern(level, p, candidates, network));
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
    public List<CraftPlanner.Pattern<GridKey>> patternsFor(GridKey key) {
        return byOutput.getOrDefault(key, List.of());
    }

    /** A bucket of fluid or material takes the room of one item; less than a bucket takes one all the same. */
    @Override
    public long space(GridKey key, long amount) {
        return key instanceof GridKey.Fluid || key instanceof GridKey.Material ? (amount + FluidAmounts.PER_BUCKET - 1) / FluidAmounts.PER_BUCKET : amount;
    }

    /** Only items grow from copies of themselves: a job holds back no fluid or material as a seed. */
    @Override
    public boolean grows(GridKey key) {
        return key instanceof GridKey.Item;
    }

    /** Every item and fluid some card makes. */
    public Set<GridKey> outputs() {
        return byOutput.keySet();
    }

    /** Whether {@code key} is made only by processing cards none of whose machines can be reached. */
    public boolean unreachable(GridKey key) {
        return unreachable.contains(key);
    }

    /** One crafting card as a planner pattern. */
    public static final class CardPattern implements Entry {
        private final CardRecipes.Resolved card;
        private final List<List<GridKey>> slots = new ArrayList<>();
        private final Map<GridKey, Long> remainders = new LinkedHashMap<>();

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
                slots.add(options.stream().<GridKey>map(GridKey.Item::new).toList());
            }
            for (int i = 0; i < 9; i++) {
                ItemStack left = card.leftover(i);
                if (!left.isEmpty()) {
                    // A copy of the item that went in (worn, or bare of its data) counts as that item, so the planner sees it come back.
                    ItemStack kind = card.returnsSelf(level, i) ? card.encoded(i) : left;
                    remainders.merge(new GridKey.Item(ItemResource.of(kind)), (long) left.getCount(), Long::sum);
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
        public GridKey output() {
            return new GridKey.Item(card.outputKey());
        }

        @Override
        public long outputCount() {
            return card.outputCount();
        }

        @Override
        public List<List<GridKey>> slots() {
            return slots;
        }

        @Override
        public Map<GridKey, Long> remainders() {
            return remainders;
        }
    }

    /**
     * One processing card as a planner pattern: its first output is what it makes, the others come along. A machine
     * slot holds one kind of item, so a set never mixes the items its slot accepts.
     */
    public static final class ProcessingPattern implements Entry {
        private final ProcessingCard card;
        private final List<List<GridKey>> slots = new ArrayList<>();
        private final List<Long> amounts = new ArrayList<>();
        private final Map<GridKey, Long> extras = new LinkedHashMap<>();

        /** {@code candidates} are the items worth trying in the slots the machines' recipe opens. */
        ProcessingPattern(ServerLevel level, ProcessingCard card, Collection<ItemResource> candidates, @Nullable CableNetwork network) {
            this.card = card;
            MachineRecipes.Match recipe = MachineRecipes.of(level, Machines.located(level, network, card));
            List<ProcessingCard.Amount> inputs = card.usedInputs();
            Set<Item> returned = card.returnedItems();
            for (int i = 0; i < inputs.size(); i++) {
                ProcessingCard.Amount input = inputs.get(i);
                List<GridKey> options = new ArrayList<>();
                options.add(input.key());
                if (!input.isFluid() && !input.isMaterial() && returned.contains(input.item().getItem())) {
                    // an item the machine hands back: any copy of it serves, worn or without its data
                    for (ItemResource candidate : candidates) {
                        GridKey key = new GridKey.Item(candidate);
                        boolean written = inputs.stream().anyMatch(other -> candidate.equals(other.item()));
                        if (!written && !options.contains(key) && candidate.getItem() == input.item().getItem()) {
                            options.add(key);
                        }
                    }
                }
                if (recipe != null && recipe.opens(i)) {
                    for (ItemResource candidate : candidates) {
                        GridKey key = new GridKey.Item(candidate);
                        // an item written in another slot stays that slot's, so a job never mistakes one for the other
                        boolean written = inputs.stream().anyMatch(other -> candidate.equals(other.item()));
                        if (!written && !options.contains(key) && recipe.accepts(level, i, candidate)) {
                            options.add(key);
                        }
                    }
                }
                slots.add(List.copyOf(options));
                amounts.add((long) input.count());
            }
            for (ProcessingCard.Amount extra : card.extras()) {
                extras.merge(extra.key(), (long) extra.count(), Long::sum);
            }
        }

        @Override
        public Card card() {
            return card;
        }

        @Override
        public GridKey output() {
            return card.main().key();
        }

        @Override
        public long outputCount() {
            return card.main().count();
        }

        @Override
        public List<List<GridKey>> slots() {
            return slots;
        }

        @Override
        public long amount(int slot) {
            return amounts.get(slot);
        }

        @Override
        public Map<GridKey, Long> remainders() {
            return extras;
        }

        @Override
        public boolean mixes() {
            return false;
        }
    }
}
