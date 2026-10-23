package dev.micolash.jasm.autocraft;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The real recipe behind a processing card whose machines are a Furnace, a Smoker or a Blast Furnace, so its input
 * slot may also take what that recipe takes (any log for charcoal). Other machines run recipes JASM can't read: their
 * cards keep to the items written on them.
 */
public final class CookingRecipes {
    private CookingRecipes() {}

    /** A card's cooking recipe: which of its used inputs the recipe cooks, the recipe, and the items its ingredient names. */
    public record Cooking(int slot, RecipeType<? extends AbstractCookingRecipe> type, RecipeHolder<? extends AbstractCookingRecipe> recipe,
            Set<Item> named) {
        /**
         * Whether {@code candidate} may go in the card's cooking slot: the machines would cook it with this very recipe
         * and no other, and it carries no extra data (a named or enchanted item is never used up by accident).
         */
        public boolean accepts(ServerLevel level, ItemResource candidate) {
            if (candidate.isEmpty() || !named.contains(candidate.getItem())) {
                return false;
            }
            ItemStack stack = candidate.toStack(1);
            if (!stack.getComponentsPatch().equals(DataComponentPatch.EMPTY)) {
                return false;
            }
            List<? extends RecipeHolder<? extends AbstractCookingRecipe>> found = level.getServer().getRecipeManager().recipeMap()
                    .getRecipesFor(type, new SingleRecipeInput(stack), level).limit(2).toList();
            return found.size() == 1 && found.getFirst().id().equals(recipe.id());
        }
    }

    /** The kind of recipe the block at {@code pos} cooks, or null if it isn't a vanilla cooking block or isn't loaded. */
    private static @Nullable RecipeType<? extends AbstractCookingRecipe> typeAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity block = level.getBlockEntity(pos);
        if (block instanceof FurnaceBlockEntity) return RecipeType.SMELTING;
        if (block instanceof BlastFurnaceBlockEntity) return RecipeType.BLASTING;
        if (block instanceof SmokerBlockEntity) return RecipeType.SMOKING;
        return null;
    }

    /**
     * The cooking recipe behind {@code card}, or null when there is none to go by: a machine isn't a cooking block of one
     * kind (or can't be looked at now), or no input is cooked into the card's result by a recipe of that kind.
     */
    public static @Nullable Cooking of(ServerLevel level, ProcessingCard card) {
        ProcessingCard.Amount main = card.main();
        if (card.machines().isEmpty() || main.isEmpty() || main.isFluid() || main.isMaterial()) {
            return null;
        }
        RecipeType<? extends AbstractCookingRecipe> type = null;
        for (ProcessingCard.Machine machine : card.machines()) {
            RecipeType<? extends AbstractCookingRecipe> here = level.isLoaded(machine.pos()) ? typeAt(level, machine.pos().relative(machine.side())) : null;
            if (here == null || type != null && type != here) {
                return null;
            }
            type = here;
        }
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        for (int i = 0; i < inputs.size(); i++) {
            ProcessingCard.Amount input = inputs.get(i);
            if (input.isFluid() || input.isMaterial()) {
                continue;
            }
            SingleRecipeInput single = new SingleRecipeInput(input.item().toStack(1));
            Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> found = find(level, type, single);
            if (found.isPresent() && ItemStack.isSameItemSameComponents(found.get().value().assemble(single), main.item().toStack(1))) {
                Set<Item> named = found.get().value().input().items().map(Holder::value).collect(Collectors.toSet());
                return new Cooking(i, type, found.get(), named);
            }
        }
        return null;
    }

    private static <T extends AbstractCookingRecipe> Optional<RecipeHolder<T>> find(ServerLevel level, RecipeType<T> type, SingleRecipeInput input) {
        return level.getServer().getRecipeManager().getRecipeFor(type, input, level);
    }
}
