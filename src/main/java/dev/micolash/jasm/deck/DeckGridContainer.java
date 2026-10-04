package dev.micolash.jasm.deck;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * A Crafting Deck's 3×3 grid. On the server every change is stored back on the Deck at once, so what sits in the
 * grid stays there, saved with the player's file like any other item, until the player moves it.
 */
public final class DeckGridContainer implements CraftingContainer {
    public static final int SIZE = 9;

    private final ItemStack deck;
    private final AbstractContainerMenu menu;
    private final boolean server;
    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public DeckGridContainer(ItemStack deck, AbstractContainerMenu menu, boolean server) {
        this.deck = deck;
        this.menu = menu;
        this.server = server;
        deck.getOrDefault(JasmComponents.DECK_GRID.get(), ItemContainerContents.EMPTY).copyInto(items);
    }

    /** Grid → Deck. */
    public void flush() {
        if (!server) {
            return;
        }
        ItemContainerContents contents = ItemContainerContents.fromItems(items);
        if (isEmpty()) {
            deck.remove(JasmComponents.DECK_GRID.get());
        } else if (!contents.equals(deck.get(JasmComponents.DECK_GRID.get()))) {
            deck.set(JasmComponents.DECK_GRID.get(), contents);
        }
    }

    @Override
    public int getWidth() {
        return 3;
    }

    @Override
    public int getHeight() {
        return 3;
    }

    @Override
    public List<ItemStack> getItems() {
        return List.copyOf(items);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        flush();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    /** Stores the grid and lets the menu work out the new result. */
    @Override
    public void setChanged() {
        flush();
        menu.slotsChanged(this);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public void fillStackedContents(StackedItemContents contents) {
        items.forEach(contents::accountSimpleStack);
    }
}
