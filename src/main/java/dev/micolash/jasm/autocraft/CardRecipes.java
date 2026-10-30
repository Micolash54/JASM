package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.crafting.RecipeChoices;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.workshop.BitlingItem;
import dev.micolash.jasm.workshop.EvolveRecipe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Turning a 3×3 grid into a Recipe Card, and a card back into a recipe the server can craft with. */
public final class CardRecipes {
    private CardRecipes() {}

    /** Why a grid can't be encoded, or the card it makes. */
    public sealed interface Encoding {
        record Card(dev.micolash.jasm.autocraft.Card card) implements Encoding {}

        record Refused(String messageKey) implements Encoding {}
    }

    /** The card for this grid (one item per slot), or why there is none. With several recipes, the first that fits. */
    public static Encoding encode(ServerLevel level, List<ItemStack> grid) {
        return encode(level, grid, null);
    }

    /**
     * The card for this grid, for recipe {@code picked} when the player chose one of several that fit. Without a pick,
     * or when it no longer fits, the first that fits, as a crafting table would choose.
     */
    public static Encoding encode(ServerLevel level, List<ItemStack> grid, @Nullable ResourceKey<Recipe<?>> picked) {
        List<ItemStack> single = new ArrayList<>();
        for (ItemStack stack : grid) {
            single.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        if (single.stream().allMatch(ItemStack::isEmpty)) {
            return new Encoding.Refused("message.jasm.terminal.empty_grid");
        }
        CraftingInput input = CraftingInput.of(3, 3, single);
        List<RecipeChoices.Choice> choices = RecipeChoices.of(level, input);
        int at = RecipeChoices.chosen(choices, picked);
        if (at < 0) {
            return new Encoding.Refused("message.jasm.terminal.no_recipe");
        }
        RecipeChoices.Choice choice = choices.get(at);
        return new Encoding.Card(new RecipeCard(choice.id(), ItemContainerContents.fromItems(single),
                ItemStackTemplate.fromNonEmptyStack(choice.result()), shapeless(choice.recipe().value())));
    }

    /** Whether the recipe book shows {@code recipe} as shapeless. */
    public static boolean shapeless(CraftingRecipe recipe) {
        return recipe.display().stream().anyMatch(d -> d instanceof ShapelessCraftingRecipeDisplay);
    }

    /**
     * A card the server can craft with: its recipe still exists and still makes the same thing from the encoded grid.
     * Null when the recipe is gone or changed (a datapack or a mod removed it). Never another recipe in its place.
     */
    public static @Nullable Resolved resolve(ServerLevel level, RecipeCard card) {
        RecipeHolder<?> holder = level.getServer().getRecipeManager().recipeMap().byKey(card.recipe());
        CraftingRecipe recipe = holder != null && holder.value() instanceof CraftingRecipe crafting ? crafting : null;
        List<ItemStack> inputs = card.inputs();
        ItemStack made = made(recipe, card, inputs, level);
        return made == null ? null : new Resolved(card, recipe, inputs, made);
    }

    /**
     * Whether {@code holder}, the card's recipe as this game knows it (null if it knows none), still makes the card's
     * result from the card's grid. The card's tooltip says "Recipe missing" when it doesn't.
     */
    public static boolean works(@Nullable RecipeHolder<?> holder, RecipeCard card, Level level) {
        CraftingRecipe recipe = holder != null && holder.value() instanceof CraftingRecipe crafting ? crafting : null;
        return made(recipe, card, card.inputs(), level) != null;
    }

    private static @Nullable ItemStack made(@Nullable CraftingRecipe recipe, RecipeCard card, List<ItemStack> inputs, Level level) {
        if (recipe == null) {
            return null;
        }
        CraftingInput input = CraftingInput.of(3, 3, inputs);
        if (!recipe.matches(input, level)) {
            return null;
        }
        ItemStack made = recipe.assemble(input);
        return ItemStack.isSameItemSameComponents(made, card.result()) && made.getCount() == card.result().getCount() ? made : null;
    }

    /** A card with its live recipe. */
    public static final class Resolved {
        private final RecipeCard card;
        private final CraftingRecipe recipe;
        private final List<ItemStack> encoded;
        private final ItemStack output;
        /** What one craft of the encoded grid leaves in each of the nine slots, worked out when first asked. */
        private @Nullable List<ItemStack> leftovers;
        /** Per slot, whether its item comes back after a craft; worked out when first asked. */
        private Boolean @Nullable [] returns;

        Resolved(RecipeCard card, CraftingRecipe recipe, List<ItemStack> encoded, ItemStack output) {
            this.card = card;
            this.recipe = recipe;
            this.encoded = encoded;
            this.output = output;
        }

        /**
         * Whether the recipe hands the item in {@code slot} back after a craft, as the same item: worn a little, or as a
         * bare copy that lost its enchantments, name or other data (Mystical Agriculture's crystals come back like that).
         * One of it then serves every craft. The copy that comes back must still fit the slot, so a recipe that asks
         * for one variant of an item (one comb type) and hands back another doesn't count.
         */
        public boolean returnsSelf(Level level, int slot) {
            if (returns == null) {
                returns = new Boolean[9];
            }
            if (returns[slot] == null) {
                ItemStack there = encoded.get(slot);
                ItemStack left = leftover(slot);
                returns[slot] = !there.isEmpty() && !left.isEmpty() && left.is(there.getItem()) && fits(level, slot, left.copyWithCount(1));
            }
            return returns[slot];
        }

        /** Whether the recipe still matches with {@code stack} in {@code slot} and the encoded items everywhere else. */
        private boolean fits(Level level, int slot, ItemStack stack) {
            NonNullList<ItemStack> test = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < 9; i++) {
                test.set(i, i == slot ? stack : encoded.get(i));
            }
            return recipe.matches(CraftingInput.of(3, 3, test), level);
        }

        /** What one craft of the encoded grid leaves in grid slot {@code slot}, or empty. */
        public ItemStack leftover(int slot) {
            if (leftovers == null) {
                // The recipe sees the grid cut down to its used rows and columns, so its leftovers are placed back by position.
                CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, encoded);
                CraftingInput input = positioned.input();
                List<ItemStack> left = recipe.getRemainingItems(input);
                NonNullList<ItemStack> bySlot = NonNullList.withSize(9, ItemStack.EMPTY);
                for (int i = 0; i < left.size() && input.width() > 0; i++) {
                    int row = positioned.top() + i / input.width();
                    int column = positioned.left() + i % input.width();
                    if (row < 3 && column < 3) {
                        bySlot.set(row * 3 + column, left.get(i));
                    }
                }
                leftovers = List.copyOf(bySlot);
            }
            return leftovers.get(slot);
        }

        public RecipeCard card() {
            return card;
        }

        public CraftingRecipe recipe() {
            return recipe;
        }

        /** The encoded item in {@code slot}, or empty. */
        public ItemStack encoded(int slot) {
            return encoded.get(slot);
        }

        /** What one craft makes. A fresh copy. */
        public ItemStack output() {
            return output.copy();
        }

        public ItemResource outputKey() {
            return ItemResource.of(output);
        }

        public int outputCount() {
            return output.getCount();
        }

        /**
         * Whether {@code candidate} may stand in for the encoded item in {@code slot}: the recipe still matches with it
         * there. Items carrying extra data (enchantments, damage, names) only stand in when the encoded item had the
         * same data, so a worn or enchanted tool is never used up by accident; an empty wafer is the exception, as it
         * holds nothing (its settings go, as at a crafting table). A slot whose item the recipe hands back
         * takes any copy of that item, whatever its wear, enchantments, name or other data, as long as the recipe still
         * takes it there: the crystal that comes back bare keeps serving, but a card with a regular crystal never takes a
         * Master one. A slot holding a fully trained critter takes any copy of it, whatever
         * its charge or name; the recipe turns away the ones whose bar isn't full.
         */
        public boolean accepts(ServerLevel level, int slot, ItemResource candidate) {
            ItemStack there = encoded.get(slot);
            if (there.isEmpty() || candidate.isEmpty()) {
                return false;
            }
            if (candidate.equals(ItemResource.of(there))) {
                return true;
            }
            ItemStack stack = candidate.toStack(1);
            if (trainedCritter(slot)) {
                if (!stack.is(there.getItem())) {
                    return false;
                }
            } else if (returnsSelf(level, slot)) {
                if (!stack.is(there.getItem())) {
                    return false;
                }
            } else if (!stack.getComponentsPatch().equals(DataComponentPatch.EMPTY) && !emptyWaferFor(level, stack, there)) {
                return false;
            }
            return fits(level, slot, stack);
        }

        /**
         * An empty wafer stands in for a blank one of its kind whatever its filter rows, name or backup, as it would at
         * a crafting table; crafting it ends the wafer like the crafting table does. A wafer holding anything never does.
         */
        private static boolean emptyWaferFor(ServerLevel level, ItemStack stack, ItemStack encoded) {
            return stack.getItem() instanceof WaferItem && stack.is(encoded.getItem()) && encoded.getComponentsPatch().isEmpty()
                    && WaferEligibility.isEmptyWafer(WaferStore.get(level.getServer()), stack);
        }

        /** Whether the recipe needs the critter in {@code slot} to have a full training bar. */
        private boolean trainedCritter(int slot) {
            ItemStack there = encoded.get(slot);
            return recipe instanceof EvolveRecipe && there.getItem() instanceof BitlingItem critter
                    && Training.full(BitlingItem.trained(there), critter.trainingRequired());
        }

        /**
         * Whether these nine inputs really make this card's output. A wafer made from used wafers carries a note of
         * which ones went in; that note isn't part of what the card makes.
         */
        public boolean makes(ServerLevel level, List<ItemStack> inputs) {
            CraftingInput input = CraftingInput.of(3, 3, inputs);
            if (!recipe.matches(input, level)) {
                return false;
            }
            ItemStack made = recipe.assemble(input);
            made.remove(JasmComponents.WAFER_MERGE.get());
            if (recipe instanceof EvolveRecipe) {
                // the grown critter keeps the charge and name of the one that went in
                return made.is(output.getItem()) && made.getCount() == output.getCount();
            }
            return ItemStack.isSameItemSameComponents(made, output);
        }
    }
}
