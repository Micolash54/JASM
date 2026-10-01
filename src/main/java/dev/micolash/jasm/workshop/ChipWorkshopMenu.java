package dev.micolash.jasm.workshop;

import dev.micolash.jasm.registry.JasmMenus;
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

/** The Workshop's menu: Blank Chip slot, the 5×3 output grid, critter slot, then the player's inventory and hotbar. */
public class ChipWorkshopMenu extends AbstractContainerMenu {
    /** The critter's own panel on the left; the Workshop's main panel starts at {@link #MAIN_X}. */
    public static final int SIDE_WIDTH = 152;
    /** The main panel joins the side panel, overlapping it a little so the two read as one. */
    public static final int MAIN_X = SIDE_WIDTH - 3;
    public static final int MAIN_WIDTH = 176;
    public static final int CRITTER_X = (SIDE_WIDTH - 16) / 2;
    public static final int CRITTER_Y = 20;
    /** The Workshop panel has two columns: the Blank Chip slot, then the output grid, both 8 from the panel's edge. */
    public static final int INPUT_X = MAIN_X + 9;
    public static final int OUTPUT_X = MAIN_X + ChipWorkshopMenu.MAIN_WIDTH - 8 - 5 * 18 + 1;
    public static final int OUTPUT_Y = 22;
    public static final int OUTPUT_COLUMNS = 5;
    /** The Blank Chip slot sits level with the middle row of the output grid. */
    public static final int SLOT_Y = OUTPUT_Y + 18;
    public static final int INVENTORY_Y = OUTPUT_Y + 3 * 18 + 20;

    public static final int BUTTON_BATCH = 0;
    public static final int BUTTON_ADVANCED = 1;

    static final int DATA_PROGRESS = 0;
    static final int DATA_TICKS = 1;
    static final int DATA_CRITTER_ENERGY_LOW = 2;
    static final int DATA_CRITTER_ENERGY_HIGH = 3;
    static final int DATA_BATTERY_LOW = 4;
    static final int DATA_BATTERY_HIGH = 5;
    static final int DATA_TRAINED_LOW = 6;
    static final int DATA_TRAINED_HIGH = 7;
    static final int DATA_REQUIRED_LOW = 8;
    static final int DATA_REQUIRED_HIGH = 9;
    static final int DATA_FLAGS = 10;
    static final int DATA_COUNT = 11;

    static final int FLAG_BATCH = 1;
    static final int FLAG_ADVANCED = 2;
    static final int FLAG_NAPPING = 4;
    static final int FLAG_WORKING = 8;
    static final int FLAG_POWERED = 16;

    /** Menu slot indices: the Workshop's own slots come first, in its container order. */
    private static final int MACHINE_SLOTS = ChipWorkshopBlockEntity.SLOTS;
    private static final int HOTBAR_START = MACHINE_SLOTS + 27;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final Container container;
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;
    private final @Nullable ChipWorkshopBlockEntity workshop;

    /** Server side. */
    public ChipWorkshopMenu(int containerId, Inventory inventory, ChipWorkshopBlockEntity workshop, ContainerData data, ContainerLevelAccess access,
            Block block) {
        this(containerId, inventory, workshop, data, access, block, workshop);
    }

    /** Client side. */
    public ChipWorkshopMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(ChipWorkshopBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return ChipWorkshopBlockEntity.accepts(slot, stack);
            }
        }, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, null);
    }

    private ChipWorkshopMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable Block block, @Nullable ChipWorkshopBlockEntity workshop) {
        super(JasmMenus.CHIP_WORKSHOP.get(), containerId);
        this.container = container;
        this.data = data;
        this.access = access;
        this.block = block;
        this.workshop = workshop;
        addSlot(new MachineSlot(container, ChipWorkshopBlockEntity.INPUT, INPUT_X, SLOT_Y));
        for (int i = 0; i < ChipWorkshopBlockEntity.OUTPUT_COUNT; i++) {
            addSlot(new MachineSlot(container, ChipWorkshopBlockEntity.OUTPUT_FIRST + i, OUTPUT_X + i % OUTPUT_COLUMNS * 18,
                    OUTPUT_Y + i / OUTPUT_COLUMNS * 18));
        }
        addSlot(new MachineSlot(container, ChipWorkshopBlockEntity.CRITTER, CRITTER_X, CRITTER_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, MAIN_X + 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, MAIN_X + 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    private int wide(int low, int high) {
        return (data.get(high) & 0xFFFF) << 16 | (data.get(low) & 0xFFFF);
    }

    public float progress() {
        int ticks = data.get(DATA_TICKS);
        return ticks <= 0 ? 0 : data.get(DATA_PROGRESS) / (float) ticks;
    }

    public int critterEnergy() {
        return wide(DATA_CRITTER_ENERGY_LOW, DATA_CRITTER_ENERGY_HIGH);
    }

    public int battery() {
        return wide(DATA_BATTERY_LOW, DATA_BATTERY_HIGH);
    }

    public int trained() {
        return wide(DATA_TRAINED_LOW, DATA_TRAINED_HIGH);
    }

    public int required() {
        return wide(DATA_REQUIRED_LOW, DATA_REQUIRED_HIGH);
    }

    private boolean flag(int flag) {
        return (data.get(DATA_FLAGS) & flag) != 0;
    }

    public boolean batch() {
        return flag(FLAG_BATCH);
    }

    public boolean advancedSelected() {
        return flag(FLAG_ADVANCED);
    }

    public boolean napping() {
        return flag(FLAG_NAPPING);
    }

    public boolean working() {
        return flag(FLAG_WORKING);
    }

    public boolean powered() {
        return flag(FLAG_POWERED);
    }

    /** The critter in the slot, as this side sees it. */
    public ItemStack critter() {
        return slots.get(MACHINE_SLOTS - 1).getItem();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (workshop == null) {
            return false;
        }
        if (id == BUTTON_BATCH) {
            workshop.toggleBatch();
            return true;
        }
        if (id == BUTTON_ADVANCED) {
            workshop.toggleAdvanced();
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return block == null || stillValid(access, player, block);
    }

    /** Shift-click: Blank Chips to the input, a critter to its slot; out of the Workshop to the hotbar, then the inventory. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved;
        if (index < MACHINE_SLOTS) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false) || moveItemStackTo(stack, MACHINE_SLOTS, HOTBAR_START, false);
        } else if (ChipWorkshopBlockEntity.accepts(ChipWorkshopBlockEntity.INPUT, stack)) {
            moved = moveItemStackTo(stack, 0, 1, false);
        } else {
            moved = ChipWorkshopBlockEntity.accepts(ChipWorkshopBlockEntity.CRITTER, stack) && moveItemStackTo(stack, MACHINE_SLOTS - 1, MACHINE_SLOTS, false);
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
        MachineSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getContainerSlot() == ChipWorkshopBlockEntity.CRITTER ? 1 : super.getMaxStackSize(stack);
        }
    }
}
