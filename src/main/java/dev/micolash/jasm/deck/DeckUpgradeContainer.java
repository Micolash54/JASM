package dev.micolash.jasm.deck;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import java.util.List;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** The Deck's upgrade slot, saved with the item after every change. */
final class DeckUpgradeContainer extends SimpleContainer {
    private final ItemStack deck;
    private final DeckMenu menu;
    private final boolean server;

    DeckUpgradeContainer(ItemStack deck, DeckMenu menu, boolean server) {
        super(1);
        this.deck = deck;
        this.menu = menu;
        this.server = server;
        deck.getOrDefault(JasmComponents.DECK_UPGRADE.get(), ItemContainerContents.EMPTY).copyInto(getItems());
    }

    void flush() {
        if (!server) return;
        if (isEmpty()) {
            deck.remove(JasmComponents.DECK_UPGRADE.get());
        } else {
            ItemContainerContents contents = ItemContainerContents.fromItems(List.of(getItem(0)));
            if (!contents.equals(deck.get(JasmComponents.DECK_UPGRADE.get()))) deck.set(JasmComponents.DECK_UPGRADE.get(), contents);
        }
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) { return stack.is(JasmItems.DIMENSION_UPGRADE.get()); }

    @Override
    public void setChanged() {
        super.setChanged();
        if (deck != null) {
            flush();
            menu.slotsChanged(this);
        }
    }
}
