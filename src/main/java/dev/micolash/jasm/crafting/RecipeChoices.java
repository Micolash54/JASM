package dev.micolash.jasm.crafting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jspecify.annotations.Nullable;

/**
 * Every crafting recipe that fits a grid, for the recipe picker on the Crafting Deck and the Encoding Terminal.
 *
 * <p>They keep the game's own order: recipe priorities a pack sets first, then recipe names. So with nothing picked,
 * the first one is exactly what a crafting table makes. Recipes that make exactly the same thing (same item, same
 * data, same amount) are listed once, the first of them.
 *
 * <p>JASM looks the picked recipe up by its name, never through the game's "first recipe that fits", so mods that
 * change that lookup can't swap the result behind the player's back.
 */
public final class RecipeChoices {
    private RecipeChoices() {}

    /** The most results the picker lists. */
    public static final int MAX = 36;

    /** A recipe that fits, and what it makes from this grid. */
    public record Choice(RecipeHolder<CraftingRecipe> recipe, ItemStack result) {
        public ResourceKey<Recipe<?>> id() {
            return recipe.id();
        }
    }

    /** What fits {@code input} now, in the game's order, one per result. */
    public static List<Choice> of(ServerLevel level, CraftingInput input) {
        if (input.isEmpty()) {
            return List.of();
        }
        List<Choice> found = new ArrayList<>();
        level.getServer().getRecipeManager().recipeMap().getRecipesFor(RecipeType.CRAFTING, input, level).forEach(holder -> {
            if (found.size() >= MAX) {
                return;
            }
            ItemStack made = holder.value().assemble(input);
            if (made.isEmpty() || !made.isItemEnabled(level.enabledFeatures())) {
                return;
            }
            for (Choice seen : found) {
                if (ItemStack.matches(seen.result(), made)) {
                    return;
                }
            }
            found.add(new Choice(holder, made));
        });
        return List.copyOf(found);
    }

    /** Where {@code id} is in {@code choices}, or -1. */
    public static int indexOf(List<Choice> choices, @Nullable ResourceKey<Recipe<?>> id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a pick still stands after the grid changed. It goes once the grid is empty, or once the grid fits some
     * recipe and the pick isn't one of them. A grid that fits nothing yet keeps it: an item taken out and put back,
     * or a recipe viewer that could only place part of the recipe, picks up where it was.
     */
    public static boolean keeps(List<Choice> choices, @Nullable ResourceKey<Recipe<?>> picked, boolean gridEmpty) {
        if (picked == null || gridEmpty) {
            return false;
        }
        return choices.isEmpty() || indexOf(choices, picked) >= 0;
    }

    /** The one to use: the pick if it fits, else the first. -1 when nothing fits. */
    public static int chosen(List<Choice> choices, @Nullable ResourceKey<Recipe<?>> picked) {
        int at = indexOf(choices, picked);
        return at >= 0 ? at : choices.isEmpty() ? -1 : 0;
    }
}
