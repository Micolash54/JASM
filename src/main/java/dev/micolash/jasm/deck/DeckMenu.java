package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Deck's menu: its wafer slots, then the player's inventory (27 slots) and hotbar (9). The Deck itself stays
 * locked in its slot while open, and the menu closes if that exact Deck leaves the slot.
 */
public class DeckMenu extends AbstractContainerMenu {
    public static final int WAFER_ROW_Y = 18;
    private static final Identifier EMPTY_WAFER = Jasm.id("container/empty_wafer");
    /** Where the inventory starts when the wafer slots fit in two rows; each further row pushes it down 18. */
    public static final int INVENTORY_Y = 150;

    private final Player player;
    private final int deckSlot;
    private final ItemStack deck;
    private final DeckWaferContainer wafers;
    private final int waferSlots;
    private final int inventoryY;
    /** Client side only: what the server has told this screen. */
    private final DeckView view = new DeckView();

    /** Server side. */
    public DeckMenu(int containerId, Inventory inventory, int deckSlot) {
        super(JasmMenus.DECK.get(), containerId);
        this.player = inventory.player;
        this.deckSlot = deckSlot;
        this.deck = inventory.getItem(deckSlot);
        this.wafers = new DeckWaferContainer(deck, player);
        this.waferSlots = wafers.getContainerSize();
        this.inventoryY = INVENTORY_Y + Math.max(0, (waferSlots + 8) / 9 - 2) * 18;

        for (int i = 0; i < waferSlots; i++) {
            addSlot(new WaferSlot(wafers, i, 8 + (i % 9) * 18, WAFER_ROW_Y + (i / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int index = 9 + row * 9 + column;
                addSlot(playerSlot(inventory, index, 8 + column * 18, inventoryY + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(playerSlot(inventory, column, 8 + column * 18, inventoryY + 58));
        }
    }

    /** Client side: the server tells which inventory slot holds the Deck. */
    public static DeckMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        return new DeckMenu(containerId, inventory, data.readVarInt());
    }

    private Slot playerSlot(Inventory inventory, int index, int x, int y) {
        return index == deckSlot ? new LockedSlot(inventory, index, x, y) : new Slot(inventory, index, x, y);
    }

    public ItemStack deck() {
        return deck;
    }

    public int deckSlot() {
        return deckSlot;
    }

    /** Top of the player's inventory in this Deck's screen (lower for Decks with more than two rows of wafers). */
    public int inventoryY() {
        return inventoryY;
    }

    public int waferSlots() {
        return waferSlots;
    }

    public DeckWaferContainer wafers() {
        return wafers;
    }

    public DeckView view() {
        return view;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.getInventory().getItem(deckSlot) == deck && deck.getItem() instanceof DeckItem;
    }

    /** The Deck's own slot can't be clicked, and number keys or the off-hand key can't swap it away. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (slotIndex >= 0 && slotIndex < slots.size() && slots.get(slotIndex) instanceof LockedSlot) {
            return;
        }
        if (input == ContainerInput.SWAP && buttonNum == deckSlot) {
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /**
     * Shift-click: a wafer moves between the Deck's wafer slots and the inventory; any other item from the
     * inventory is stored on the Deck's wafers.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || slot instanceof LockedSlot) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (index < waferSlots) {
            // Hotbar first, left to right, then the inventory from its top-left slot.
            int hotbar = waferSlots + 27;
            if (!moveItemStackTo(stack, hotbar, hotbar + 9, false)) {
                moveItemStackTo(stack, waferSlots, hotbar, false);
            }
            slot.setChanged();
        } else if (stack.getItem() instanceof WaferItem) {
            moveItemStackTo(stack, 0, waferSlots, false);
            slot.setChanged();
        } else if (player instanceof ServerPlayer serverPlayer) {
            wafers.flush();
            DeckStorage.deposit(WaferStore.get(serverPlayer.level().getServer()), deck, stack, serverPlayer);
            wafers.reload();
            slot.setChanged();
        }
        return ItemStack.EMPTY;
    }

    /** Vanilla slot code can change a wafer stack without telling the container; catch up every tick. */
    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide()) {
            wafers.flush();
        }
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            wafers.flush();
        }
    }

    /** Accepts wafers only, one per slot. */
    private static final class WaferSlot extends Slot {
        WaferSlot(DeckWaferContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        /** Shows a faint wafer while empty. */
        @Override
        public Identifier getNoItemIcon() {
            return EMPTY_WAFER;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof WaferItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** The slot holding the open Deck. */
    private static final class LockedSlot extends Slot {
        LockedSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
