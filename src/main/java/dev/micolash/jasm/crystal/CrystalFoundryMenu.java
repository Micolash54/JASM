package dev.micolash.jasm.crystal;

import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.transfer.SpeedUpgradeSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

/** The Foundry's menu: the seed slot, the 3×3 output grid, the upgrade column, then the player's inventory and hotbar. */
public class CrystalFoundryMenu extends AbstractContainerMenu implements MachineView {
    public static final int INPUT_X = 26;
    public static final int INPUT_Y = 39;
    public static final int OUTPUT_X = 108;
    public static final int OUTPUT_Y = 21;
    public static final int ROW_Y = 82;
    public static final int INVENTORY_Y = 108;
    /** The upgrade column hangs past the right edge of the 176 wide panel, as on the bays. */
    public static final int UPGRADE_X = 176 + 2;
    public static final int UPGRADE_Y = 29;

    static final int DATA_PROGRESS = 0;
    static final int DATA_TICKS = 1;
    static final int DATA_MADE = 2;
    static final int DATA_PER_SEED = 3;
    static final int DATA_ENERGY_LOW = 4;
    static final int DATA_ENERGY_HIGH = 5;
    static final int DATA_FLAGS = 6;
    static final int DATA_SIDES = 7;
    static final int DATA_REDSTONE = 8;
    static final int DATA_AUTO_OUTPUT = 9;
    static final int DATA_COUNT = 10;

    static final int FLAG_GROWING = 1;
    static final int FLAG_POWERED = 2;
    static final int FLAG_FULL = 4;
    static final int FLAG_PAUSED = 8;

    /** The redstone key; the I/O grid's face buttons start well above it. */
    public static final int BUTTON_REDSTONE = 0;

    private static final int MACHINE_SLOTS = CrystalFoundryBlockEntity.SLOTS;
    private static final int HOTBAR_START = MACHINE_SLOTS + 27;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final Container container;
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;
    private @Nullable BlockPos shownAt;

    /** Server side. */
    public CrystalFoundryMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable Block block) {
        super(JasmMenus.CRYSTAL_FOUNDRY.get(), containerId);
        this.container = container;
        this.data = data;
        this.access = access;
        this.block = block;
        addSlot(new MachineSlot(container, CrystalFoundryBlockEntity.INPUT, INPUT_X, INPUT_Y));
        for (int i = 0; i < CrystalFoundryBlockEntity.OUTPUT_COUNT; i++) {
            addSlot(new MachineSlot(container, CrystalFoundryBlockEntity.OUTPUT_FIRST + i, OUTPUT_X + i % 3 * 18, OUTPUT_Y + i / 3 * 18));
        }
        for (int i = 0; i < CrystalFoundryBlockEntity.UPGRADES; i++) {
            addSlot(new SpeedUpgradeSlot(container, CrystalFoundryBlockEntity.UPGRADE_START + i, UPGRADE_X, UPGRADE_Y + i * 18));
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

    /** Client side. */
    public CrystalFoundryMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(CrystalFoundryBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return CrystalFoundryBlockEntity.accepts(this, slot, stack);
            }
        }, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    public float progress() {
        int ticks = data.get(DATA_TICKS);
        return ticks <= 0 ? 0 : data.get(DATA_PROGRESS) / (float) ticks;
    }

    public int made() {
        return data.get(DATA_MADE);
    }

    public int perSeed() {
        return data.get(DATA_PER_SEED);
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public boolean growing() {
        return (data.get(DATA_FLAGS) & FLAG_GROWING) != 0;
    }

    public boolean powered() {
        return (data.get(DATA_FLAGS) & FLAG_POWERED) != 0;
    }

    public boolean full() {
        return (data.get(DATA_FLAGS) & FLAG_FULL) != 0;
    }

    /** Stopped by its Redstone Upgrade's mode. */
    public boolean paused() {
        return (data.get(DATA_FLAGS) & FLAG_PAUSED) != 0;
    }

    public RedstoneMode redstoneMode() {
        return RedstoneMode.byId(data.get(DATA_REDSTONE));
    }

    public boolean hasRedstoneUpgrade() {
        for (int i = 0; i < CrystalFoundryBlockEntity.UPGRADES; i++) {
            if (getSlot(CrystalFoundryBlockEntity.UPGRADE_START + i).getItem().is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        }
        return false;
    }

    /** The I/O grid's item faces, two bits each. */
    public int sides() {
        return data.get(DATA_SIDES);
    }

    /** Client side, opened from the block: the opening data says where it stands, so the I/O grid can show its neighbours. */
    public static CrystalFoundryMenu client(int containerId, Inventory inventory, @Nullable RegistryFriendlyByteBuf buf) {
        CrystalFoundryMenu menu = new CrystalFoundryMenu(containerId, inventory);
        if (buf != null && buf.isReadable()) menu.shownAt = buf.readBlockPos();
        return menu;
    }

    /** Where the machine stands, as the client was told when the screen opened; null if it wasn't. */
    public @Nullable BlockPos shownAt() {
        return shownAt;
    }

    /** Whether the machine pushes its results out on its own. */
    public boolean autoOutput() {
        return data.get(DATA_AUTO_OUTPUT) != 0;
    }

    /** The redstone key and the I/O grid's face buttons. */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(container instanceof CrystalFoundryBlockEntity foundry)) return false;
        if (id == BUTTON_REDSTONE) {
            if (!foundry.hasRedstoneUpgrade()) return false;
            foundry.setRedstoneMode(foundry.redstoneMode().next());
            return true;
        }
        return foundry.sides().click(id);
    }

    @Override
    public boolean stillValid(Player player) {
        return block == null || stillValid(access, player, block);
    }

    /** Shift-click: seeds to the seed slot, upgrades to the upgrade column; out of the Foundry to the hotbar, then the inventory. */
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
        } else if (stack.is(JasmItems.SPEED_UPGRADE.get())) {
            // Each upgrade slot holds one, and one move fills one empty slot.
            moved = false;
            while (!stack.isEmpty() && moveItemStackTo(stack, CrystalFoundryBlockEntity.UPGRADE_START, MACHINE_SLOTS, false)) moved = true;
        } else if (stack.is(JasmItems.REDSTONE_UPGRADE.get())) {
            moved = moveItemStackTo(stack, CrystalFoundryBlockEntity.UPGRADE_START, MACHINE_SLOTS, false);
        } else {
            moved = CrystalFoundryBlockEntity.accepts(CrystalFoundryBlockEntity.INPUT, stack) && moveItemStackTo(stack, 0, 1, false);
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
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return access.evaluate((level, pos) -> pos).orElse(null);
    }
}
