package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmBlocks;
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
import org.jspecify.annotations.Nullable;

/** The Recipe Rack's menu: 16 card slots in two rows, then the player's inventory (27) and hotbar (9). */
public class RecipeRackMenu extends AbstractContainerMenu {
    public static final int CARDS_X = 17;
    public static final int CARDS_Y = 22;
    public static final int INVENTORY_Y = 90;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_COUNT = 3;

    private static final Identifier EMPTY_CARD = Jasm.id("container/empty_card");

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable RecipeRackBlockEntity rack;

    /** Server side. */
    public RecipeRackMenu(int containerId, Inventory inventory, RecipeRackBlockEntity rack, ContainerLevelAccess access) {
        this(containerId, inventory, rack, rack.data(), access, rack);
    }

    /** Client side. */
    public RecipeRackMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(RecipeRackBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return RecipeRackBlockEntity.accepts(stack);
            }
        }, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    private RecipeRackMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable RecipeRackBlockEntity rack) {
        super(JasmMenus.RECIPE_RACK.get(), containerId);
        this.data = data;
        this.access = access;
        this.rack = rack;
        for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) {
            addSlot(new CardSlot(container, i, CARDS_X + (i % 8) * 18, CARDS_Y + (i / 8) * 18));
        }
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

    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    public int capacity() {
        return RecipeRackBlockEntity.CAPACITY;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        return rack == null || !rack.isRemoved() && stillValid(access, player, JasmBlocks.RECIPE_RACK.get()) && MachineAccess.canUse(rack, player);
    }

    /** Shift-click: cards into the first free card slot; out of the rack, to the hotbar first, then the inventory. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        int inventory = RecipeRackBlockEntity.SLOTS;
        int hotbar = inventory + 27;
        boolean moved = index < inventory
                ? moveItemStackTo(stack, hotbar, hotbar + 9, false) || moveItemStackTo(stack, inventory, hotbar, false)
                : RecipeRackBlockEntity.accepts(stack) && moveItemStackTo(stack, 0, inventory, false);
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

    private static final class CardSlot extends Slot {
        CardSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return RecipeRackBlockEntity.accepts(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public Identifier getNoItemIcon() {
            return EMPTY_CARD;
        }
    }
}
