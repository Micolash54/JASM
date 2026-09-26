package dev.micolash.jasm.autocraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Turning a 3×3 grid into a Recipe Card, and a card back into a recipe the server can craft with. */
public final class CardRecipes {
    private CardRecipes() {}

    /** Why a grid can't be encoded, or the card it makes. */
    public sealed interface Encoding {
        record Card(RecipeCard card) implements Encoding {}

        record Refused(String messageKey) implements Encoding {}
    }

    /** The card for this grid (one item per slot), or why there is none. */
    public static Encoding encode(ServerLevel level, List<ItemStack> grid) {
        List<ItemStack> single = new ArrayList<>();
        for (ItemStack stack : grid) {
            single.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        if (single.stream().allMatch(ItemStack::isEmpty)) {
            return new Encoding.Refused("message.jasm.terminal.empty_grid");
        }
        CraftingInput input = CraftingInput.of(3, 3, single);
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (recipe.isEmpty()) {
            return new Encoding.Refused("message.jasm.terminal.no_recipe");
        }
        ItemStack output = recipe.get().value().assemble(input);
        if (output.isEmpty()) {
            return new Encoding.Refused("message.jasm.terminal.no_recipe");
        }
        boolean shapeless = recipe.get().value().display().stream().anyMatch(d -> d instanceof ShapelessCraftingRecipeDisplay);
        return new Encoding.Card(new RecipeCard(recipe.get().id(), ItemContainerContents.fromItems(single),
                ItemStackTemplate.fromNonEmptyStack(output), shapeless));
    }

    /**
     * A card the server can craft with: its recipe still exists and still makes the same thing from the encoded grid.
     * Null when the recipe is gone or changed (a datapack or a mod removed it).
     */
    public static @Nullable Resolved resolve(ServerLevel level, RecipeCard card) {
        Optional<RecipeHolder<?>> holder = level.getServer().getRecipeManager().byKey(card.recipe());
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe recipe)) {
            return null;
        }
        List<ItemStack> inputs = card.inputs();
        CraftingInput input = CraftingInput.of(3, 3, inputs);
        if (!recipe.matches(input, level)) {
            return null;
        }
        ItemStack made = recipe.assemble(input);
        if (!ItemStack.isSameItemSameComponents(made, card.result())) {
            return null;
        }
        return new Resolved(card, recipe, inputs, made);
    }

    /** A card with its live recipe. */
    public static final class Resolved {
        private final RecipeCard card;
        private final CraftingRecipe recipe;
        private final List<ItemStack> encoded;
        private final ItemStack output;

        Resolved(RecipeCard card, CraftingRecipe recipe, List<ItemStack> encoded, ItemStack output) {
            this.card = card;
            this.recipe = recipe;
            this.encoded = encoded;
            this.output = output;
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
         * same data, so a worn or enchanted tool is never used up by accident.
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
            if (!stack.getComponentsPatch().equals(DataComponentPatch.EMPTY)) {
                return false;
            }
            NonNullList<ItemStack> test = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < 9; i++) {
                test.set(i, i == slot ? stack : encoded.get(i));
            }
            return recipe.matches(CraftingInput.of(3, 3, test), level);
        }

        /** Whether these nine inputs really make this card's output. */
        public boolean makes(ServerLevel level, List<ItemStack> inputs) {
            CraftingInput input = CraftingInput.of(3, 3, inputs);
            return recipe.matches(input, level) && ItemStack.isSameItemSameComponents(recipe.assemble(input), output);
        }
    }
}
