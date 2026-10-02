package dev.micolash.jasm.station;

import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.core.BlockPos;
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

/** The station's menu: the critter slot, then the player's inventory and hotbar. */
public class BitlingStationMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = 176;
    public static final int CRITTER_X = 12;
    public static final int CRITTER_Y = 24;
    public static final int INVENTORY_Y = 118;

    static final int DATA_STATUS = 0;
    static final int DATA_KNOCKED_OUT = 1;
    static final int DATA_RADIUS = 2;
    static final int DATA_RADIUS_MAX = 3;
    static final int DATA_ENERGY_LOW = 4;
    static final int DATA_ENERGY_HIGH = 5;
    static final int DATA_BATTERY_LOW = 6;
    static final int DATA_BATTERY_HIGH = 7;
    static final int DATA_COUNT = 8;

    /** Menu button ids from this up are "set the roaming radius to id - BUTTON_RADIUS". */
    public static final int BUTTON_RADIUS = 100;

    private static final int HOTBAR_START = 1 + 27;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;
    private final @Nullable BitlingStationBlockEntity station;

    /** Server side. */
    public BitlingStationMenu(int containerId, Inventory inventory, BitlingStationBlockEntity station, ContainerData data, ContainerLevelAccess access,
            Block block) {
        this(containerId, inventory, station, data, access, block, station);
    }

    /** Client side. */
    public BitlingStationMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(BitlingStationBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return BitlingStationBlockEntity.accepts(slot, stack);
            }
        }, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, null);
    }

    private BitlingStationMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable Block block, @Nullable BitlingStationBlockEntity station) {
        super(JasmMenus.BITLING_STATION.get(), containerId);
        this.data = data;
        this.access = access;
        this.block = block;
        this.station = station;
        addSlot(new Slot(container, BitlingStationBlockEntity.SLOT, CRITTER_X, CRITTER_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return container.canPlaceItem(getContainerSlot(), stack);
            }

            @Override
            public int getMaxStackSize(ItemStack stack) {
                return 1;
            }
        });
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

    private int wide(int low, int high) {
        return (data.get(high) & 0xFFFF) << 16 | (data.get(low) & 0xFFFF);
    }

    public StationStatus status() {
        return StationStatus.of(data.get(DATA_STATUS));
    }

    public int knockedOutSeconds() {
        return data.get(DATA_KNOCKED_OUT);
    }

    public int radius() {
        return data.get(DATA_RADIUS);
    }

    public int maxRadius() {
        return data.get(DATA_RADIUS_MAX);
    }

    public int critterEnergy() {
        return wide(DATA_ENERGY_LOW, DATA_ENERGY_HIGH);
    }

    public int battery() {
        return wide(DATA_BATTERY_LOW, DATA_BATTERY_HIGH);
    }

    /** The critter in the slot, as this side sees it. */
    public ItemStack critter() {
        return slots.getFirst().getItem();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (station == null || id < BUTTON_RADIUS) {
            return false;
        }
        station.setRadius(id - BUTTON_RADIUS);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return block == null || stillValid(access, player, block);
    }

    /** Shift-click: a critter to its slot; out of the station to the hotbar, then the inventory. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved;
        if (index == 0) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false) || moveItemStackTo(stack, 1, HOTBAR_START, false);
        } else {
            moved = BitlingStationBlockEntity.accepts(BitlingStationBlockEntity.SLOT, stack) && moveItemStackTo(stack, 0, 1, false);
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

    @Override
    public @Nullable BlockPos machinePos() {
        return station == null ? null : station.getBlockPos();
    }
}
