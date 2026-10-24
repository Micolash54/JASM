package dev.micolash.jasm.deck;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/**
 * The Crafting Deck's result slot. It works like the crafting table's, but is JASM's own on purpose: mods that add a
 * recipe-choice button to any screen whose result slot is the game's own (Polymorph does) leave the Deck alone, so it
 * never shows two buttons or has its result changed behind its back. The Deck has its own picker.
 */
public final class DeckResultContainer implements Container, RecipeCraftingHolder {
    private ItemStack stack = ItemStack.EMPTY;
    private @Nullable RecipeHolder<?> recipeUsed;

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return stack;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return removeItemNoUpdate(slot);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack taken = stack;
        stack = ItemStack.EMPTY;
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        stack = ItemStack.EMPTY;
    }

    @Override
    public void setRecipeUsed(@Nullable RecipeHolder<?> recipeUsed) {
        this.recipeUsed = recipeUsed;
    }

    @Override
    public @Nullable RecipeHolder<?> getRecipeUsed() {
        return recipeUsed;
    }
}
