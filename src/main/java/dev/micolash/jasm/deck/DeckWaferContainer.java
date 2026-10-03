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
 * <p>Wafers never stack, so vanilla slot code only ever puts a stack in or takes one out; it never grows or
 * shrinks one in place, which is what makes writing straight through safe. On the client the slots are filled by
 * the server's slot updates, as in any menu.
 */
public final class DeckWaferContainer implements Container {
    private final ItemStack deck;
    private final Player player;
    private final NonNullList<ItemStack> stacks;
    /** The Deck's wafers these stacks were read from; when the Deck holds something else, they are read again. */
    private @Nullable DeckWafers seen;

    public DeckWaferContainer(ItemStack deck, Player player) {
        this.deck = deck;
        this.player = player;
        this.stacks = NonNullList.withSize(((DeckItem) deck.getItem()).tier().slots(), ItemStack.EMPTY);
        sync();
    }

    private boolean server() {
        return !player.level().isClientSide();
    }

    /** Reads the stacks again if the Deck's wafers changed since they were last read. Server only. */
    private void sync() {
        DeckWafers now = DeckItem.wafers(deck);
        if (seen == null || (server() && now != seen)) {
            seen = now;
            for (int i = 0; i < stacks.size(); i++) {
                stacks.set(i, now.get(i));
            }
        }
    }

    /** Puts {@code stack} in {@code slot}, here and on the Deck. */
    private void store(int slot, ItemStack stack) {
        sync();
        stacks.set(slot, stack);
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
        return stack.getItem() instanceof WaferItem;
    }

    /** Nothing to do: every change already went onto the Deck. */
    @Override
    public void setChanged() {}

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
