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

/**
 * The Deck's wafer slots while its menu is open. Vanilla slot code changes stacks in place, so the menu works on
 * these working copies and {@link #flush} stores them back on the Deck after every change and every tick.
 * {@link #reload} picks up changes made to the Deck directly (a wafer blanked or set up by a storage operation).
 */
public final class DeckWaferContainer implements Container {
    private final ItemStack deck;
    private final Player player;
    private final NonNullList<ItemStack> wafers;

    public DeckWaferContainer(ItemStack deck, Player player) {
        this.deck = deck;
        this.player = player;
        this.wafers = NonNullList.withSize(((DeckItem) deck.getItem()).tier().slots(), ItemStack.EMPTY);
        reload();
    }

    /** Working copies ← Deck. */
    public void reload() {
        DeckWafers stored = DeckItem.wafers(deck);
        for (int i = 0; i < wafers.size(); i++) {
            wafers.set(i, stored.get(i));
        }
    }

    /** Working copies → Deck, if anything differs. */
    public void flush() {
        DeckWafers stored = DeckItem.wafers(deck);
        DeckWafers updated = stored;
        for (int i = 0; i < wafers.size(); i++) {
            if (!ItemStack.matches(stored.get(i), wafers.get(i))) {
                updated = updated.with(i, wafers.get(i));
            }
        }
        if (updated != stored) {
            deck.set(JasmComponents.DECK_WAFERS.get(), updated);
        }
    }

    @Override
    public int getContainerSize() {
        return wafers.size();
    }

    @Override
    public boolean isEmpty() {
        return wafers.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return wafers.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(wafers, slot, amount);
        setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(wafers, slot);
        setChanged();
        return removed;
    }

    /** A wafer put into the Deck is activated at once, so any older copy of it stops working. */
    @Override
    public void setItem(int slot, ItemStack stack) {
        wafers.set(slot, stack);
        if (player instanceof ServerPlayer serverPlayer && stack.getItem() instanceof WaferItem
                && (stack.get(JasmComponents.WAFER_IDENTITY.get()) instanceof WaferIdentity || WaferMerge.isPending(stack))) {
            WaferValidator.validate(WaferStore.get(serverPlayer.level().getServer()), stack, WaferValidator.Mode.ACTIVATE, serverPlayer);
            if (stack.isEmpty()) {
                wafers.set(slot, ItemStack.EMPTY);
            }
        }
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.getItem() instanceof WaferItem;
    }

    @Override
    public void setChanged() {
        if (!player.level().isClientSide()) {
            flush();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        wafers.clear();
        setChanged();
    }
}
