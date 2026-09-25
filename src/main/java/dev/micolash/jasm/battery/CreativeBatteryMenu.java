package dev.micolash.jasm.battery;

import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import org.jspecify.annotations.Nullable;

/** The Creative Battery's menu: the charging slot, then the player's inventory (27 slots) and hotbar (9). */
public class CreativeBatteryMenu extends AbstractContainerMenu {
    public static final int SLOT_X = 80;
    public static final int SLOT_Y = 26;
    public static final int INVENTORY_Y = 84;

    private final Container slot;
    private final ContainerLevelAccess access;

    /** Server side. */
    public CreativeBatteryMenu(int containerId, Inventory inventory, Container slot, ContainerLevelAccess access) {
        super(JasmMenus.CREATIVE_BATTERY.get(), containerId);
        this.slot = slot;
        this.access = access;
        addSlot(new ChargingSlot(slot, SLOT_X, SLOT_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
    }

    /** Client side. */
    public CreativeBatteryMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, chargingSlot(() -> {}), ContainerLevelAccess.NULL);
    }

    /** A one-item container that only takes items that store FE. {@code onChange} runs whenever its item changes. */
    static SimpleContainer chargingSlot(Runnable onChange) {
        return new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                onChange.run();
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean canPlaceItem(int index, ItemStack stack) {
                return canCharge(stack);
            }
        };
    }

    /** The item's battery, read from a copy so nothing changes. Null when the item stores no FE. */
    public static @Nullable EnergyHandler batteryOf(ItemStack stack) {
        return stack.isEmpty() ? null : ItemAccess.forStack(stack.copy()).getCapability(Capabilities.Energy.ITEM);
    }

    public static boolean canCharge(ItemStack stack) {
        return batteryOf(stack) != null;
    }

    public ItemStack charging() {
        return slot.getItem(0);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, JasmBlocks.CREATIVE_BATTERY.get());
    }

    /**
     * Shift-click moves the charging item to the leftmost free hotbar slot, or failing that the first free inventory
     * slot from the top left; a chargeable item from the inventory goes into the charging slot.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved = index == 0
                ? moveItemStackTo(stack, 28, 37, false) || moveItemStackTo(stack, 1, 28, false)
                : canCharge(stack) && moveItemStackTo(stack, 0, 1, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            clicked.setByPlayer(ItemStack.EMPTY);
        } else {
            clicked.setChanged();
        }
        return before;
    }

    private static final class ChargingSlot extends Slot {
        ChargingSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return canCharge(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
