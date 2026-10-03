package dev.micolash.jasm.delivery;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Server side: a player's inbox as menu slots. It reads and writes the saved inbox directly, so there is never a
 * second copy to keep in step. Taking a stack out moves the ones after it up.
 */
public final class InboxContainer implements Container {
    private final MinecraftServer server;
    private final UUID player;

    public InboxContainer(MinecraftServer server, UUID player) {
        this.server = server;
        this.player = player;
    }

    private DeliveryState state() {
        return DeliveryState.get(server);
    }

    private List<ItemStack> items() {
        return state().inbox(player);
    }

    @Override
    public int getContainerSize() {
        return DeliveryState.INBOX;
    }

    @Override
    public boolean isEmpty() {
        return !state().hasInbox(player);
    }

    @Override
    public ItemStack getItem(int slot) {
        List<ItemStack> items = items();
        return slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack there = getItem(slot);
        if (there.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = there.split(amount);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack there = getItem(slot);
        if (there.isEmpty()) return ItemStack.EMPTY;
        ItemStack taken = there.copy();
        there.setCount(0);
        setChanged();
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        List<ItemStack> items = items();
        if (slot < items.size()) items.set(slot, stack);
        else if (!stack.isEmpty()) items.add(stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        state().inboxChanged(player);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public void clearContent() {
        items().clear();
        setChanged();
    }
}
