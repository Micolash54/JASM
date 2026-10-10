package dev.micolash.jasm.deck;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferMerge;
import dev.micolash.jasm.wafer.WaferValidator;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's wafer slots while its menu is open. The Deck itself holds the wafers; this is a window onto it. Every
 * change made through a slot goes straight onto the Deck, and every read first checks whether the Deck changed
 * behind it (a storage operation blanked or set up a wafer), so there is never a second copy to keep in step.
 *
 * <p>Vanilla slot code also changes a slot's stack in place and only calls {@link #setChanged} afterwards: a
 * shift-click shrinks the wafer to nothing where it sits. Those changes are caught by comparing each slot with what
 * was last saved, and written onto the Deck in {@link #flush}. On the client the slots are filled by the server's
 * slot updates, as in any menu.
 */
public final class DeckWaferContainer implements Container {
    private final ItemStack deck;
    private final Player player;
    private final NonNullList<ItemStack> stacks;
    /** What each slot held when last read from or saved to the Deck, to spot a stack changed in place. */
    private final NonNullList<ItemStack> saved;
    /** Slots that take wafers; the ones after them hold wafers left over from a bigger Deck and only give them back. */
    private final int usable;
    /** The Deck's wafers these stacks were read from; when the Deck holds something else, they are read again. */
    private @Nullable DeckWafers seen;

    public DeckWaferContainer(ItemStack deck, Player player, int size) {
        this.deck = deck;
        this.player = player;
        this.usable = Math.min(size, ((DeckItem) deck.getItem()).tier().slots());
        this.stacks = NonNullList.withSize(size, ItemStack.EMPTY);
        this.saved = NonNullList.withSize(size, ItemStack.EMPTY);
        sync();
    }

    /** A slot past the tier's count: its wafer can only be taken out. */
    public boolean isOverflow(int slot) {
        return slot >= usable;
    }

    public int usable() {
        return usable;
    }

    private boolean server() {
        return !player.level().isClientSide();
    }

    /** Reads the stacks again when the Deck's wafers changed behind them, keeping slots changed in place. Server only. */
    private void sync() {
        if (seen == null) {
            read(DeckItem.wafers(deck));
        } else if (server() && DeckItem.wafers(deck) != seen) {
            flush();
        }
    }

    private void read(DeckWafers wafers) {
        seen = wafers;
        for (int i = 0; i < stacks.size(); i++) {
            stacks.set(i, wafers.get(i));
            saved.set(i, stacks.get(i).copy());
        }
    }

    /**
     * Saves every slot whose stack was changed in place onto the Deck, then catches up with anything the Deck changed
     * behind this window. Server only; cheap when nothing changed.
     */
    public void flush() {
        if (!server() || seen == null) {
            return;
        }
        DeckWafers now = DeckItem.wafers(deck);
        DeckWafers updated = now;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!ItemStack.matches(stack, saved.get(i))) {
                if (stack.isEmpty()) {
                    stack = ItemStack.EMPTY;
                    stacks.set(i, stack);
                }
                updated = updated.with(i, stack);
                saved.set(i, stack.copy());
            }
        }
        if (updated != now) {
            deck.set(JasmComponents.DECK_WAFERS.get(), updated);
        }
        if (now != seen) {
            read(updated);
        } else {
            seen = updated;
        }
    }

    /** Puts {@code stack} in {@code slot}, here and on the Deck. */
    private void store(int slot, ItemStack stack) {
        sync();
        stacks.set(slot, stack);
        saved.set(slot, stack.copy());
        if (server()) {
            seen = seen.with(slot, stack);
            deck.set(JasmComponents.DECK_WAFERS.get(), seen);
        }
    }

    @Override
    public int getContainerSize() {
        return stacks.size();
    }

    @Override
    public boolean isEmpty() {
        sync();
        return stacks.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        sync();
        return stacks.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        sync();
        ItemStack removed = ContainerHelper.removeItem(stacks, slot, amount);
        store(slot, stacks.get(slot));
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        sync();
        ItemStack removed = ContainerHelper.takeItem(stacks, slot);
        store(slot, ItemStack.EMPTY);
        return removed;
    }

    /** A wafer put into the Deck is activated at once, so any older copy of it stops working. */
    @Override
    public void setItem(int slot, ItemStack stack) {
        if (player instanceof ServerPlayer serverPlayer && stack.getItem() instanceof WaferItem
                && (stack.get(JasmComponents.WAFER_IDENTITY.get()) instanceof WaferIdentity || WaferMerge.isPending(stack))) {
            WaferValidator.validate(WaferStore.get(serverPlayer.level().getServer()), stack, WaferValidator.Mode.ACTIVATE, serverPlayer);
            if (stack.isEmpty()) {
                stack = ItemStack.EMPTY;
            }
        }
        store(slot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !isOverflow(slot) && stack.getItem() instanceof WaferItem;
    }

    /** A slot's stack may have changed in place: save it onto the Deck now. */
    @Override
    public void setChanged() {
        flush();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < stacks.size(); i++) {
            store(i, ItemStack.EMPTY);
        }
    }
}
