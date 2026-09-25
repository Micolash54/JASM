package dev.micolash.jasm.generator;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/** The generator's menu: fuel slot, charging slot, then the player's inventory (27 slots) and hotbar (9). */
public class CombustionGeneratorMenu extends AbstractContainerMenu {
    public static final int FUEL_X = 26;
    public static final int CHARGE_X = 134;
    public static final int SLOT_Y = 44;
    public static final int INVENTORY_Y = 84;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    /** How much of the current fuel item is left, 0 to 1000. */
    static final int DATA_FLAME = 2;
    static final int DATA_OUTPUT = 3;
    static final int DATA_CAPACITY_LOW = 4;
    static final int DATA_CAPACITY_HIGH = 5;
    static final int DATA_COUNT = 6;

    private static final Identifier EMPTY_FUEL = Jasm.id("container/empty_fuel");
    private static final Identifier EMPTY_BOLT = Jasm.id("container/empty_bolt");

    private final Container container;
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;

    /** Server side. */
    public CombustionGeneratorMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable Block block) {
        super(JasmMenus.COMBUSTION_GENERATOR.get(), containerId);
        this.container = container;
        this.data = data;
        this.access = access;
        this.block = block;
        addSlot(new MachineSlot(container, CombustionGeneratorBlockEntity.FUEL_SLOT, FUEL_X, SLOT_Y, EMPTY_FUEL));
        addSlot(new MachineSlot(container, CombustionGeneratorBlockEntity.CHARGE_SLOT, CHARGE_X, SLOT_Y, EMPTY_BOLT));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    /** Client side. */
    public CombustionGeneratorMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(2) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return CombustionGeneratorBlockEntity.accepts(slot, stack);
            }
        }, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    /** Stored FE. Sent in two halves, since each synced value is only 16 bits. */
    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    /** FE the generator holds when full. */
    public int capacity() {
        return (data.get(DATA_CAPACITY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_CAPACITY_LOW) & 0xFFFF);
    }

    /** How much of the burning fuel item is left, from 0 to 1. */
    public float flame() {
        return data.get(DATA_FLAME) / 1000F;
    }

    /** FE made in the last tick; 0 while waiting for fuel or for room in the buffer. */
    public int output() {
        return data.get(DATA_OUTPUT);
    }

    @Override
    public boolean stillValid(Player player) {
        return block == null || stillValid(access, player, block);
    }

    /**
     * Shift-click: fuel goes to the fuel slot and chargeable items to the charging slot. Out of the generator,
     * items go to the leftmost free hotbar slot, then the inventory from its top-left slot.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved;
        if (index < 2) {
            moved = moveItemStackTo(stack, 29, 38, false) || moveItemStackTo(stack, 2, 29, false);
        } else if (CombustionGeneratorBlockEntity.isFuel(stack)) {
            moved = moveItemStackTo(stack, 0, 1, false);
        } else {
            moved = CreativeBatteryMenu.canCharge(stack) && moveItemStackTo(stack, 1, 2, false);
        }
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

    private static final class MachineSlot extends Slot {
        private final Identifier emptyIcon;

        MachineSlot(Container container, int index, int x, int y, Identifier emptyIcon) {
            super(container, index, x, y);
            this.emptyIcon = emptyIcon;
        }

        @Override
        public Identifier getNoItemIcon() {
            return emptyIcon;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getContainerSlot() == CombustionGeneratorBlockEntity.CHARGE_SLOT ? 1 : super.getMaxStackSize(stack);
        }
    }
}
