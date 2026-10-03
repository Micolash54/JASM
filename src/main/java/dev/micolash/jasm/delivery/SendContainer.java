package dev.micolash.jasm.delivery;

import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.registry.JasmComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** The Deck to Deck send grid, saved on the Deck after every change like the crafting grid. */
public final class SendContainer extends SimpleContainer {
    public static final int SIZE = 9;

    private final ItemStack deck;
    private final boolean server;

    public SendContainer(ItemStack deck, boolean server) {
        super(SIZE);
        this.deck = deck;
        this.server = server;
        deck.getOrDefault(JasmComponents.DECK_SEND.get(), ItemContainerContents.EMPTY).copyInto(getItems());
    }

    /** Grid → Deck. */
    public void flush() {
        if (!server) return;
        if (isEmpty()) {
            deck.remove(JasmComponents.DECK_SEND.get());
        } else {
            ItemContainerContents contents = ItemContainerContents.fromItems(getItems());
            if (!contents.equals(deck.get(JasmComponents.DECK_SEND.get()))) deck.set(JasmComponents.DECK_SEND.get(), contents);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return DeckMenu.allowedInGrid(stack);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        flush();
    }
}
