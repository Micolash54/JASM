package dev.micolash.jasm.tether;

import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmBlocks;
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
import org.jspecify.annotations.Nullable;

/**
 * The Network Tether's panel: the operator slot, what the operator says, how many chunks it holds and what it pays, and
 * the player's inventory. Only players trusted on the network may open it or move the operator.
 */
public class NetworkTetherMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = 176;
    public static final int SLOT_Y = 20;
    public static final int OPERATOR_X = 8;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 86;

    static final int DATA_STATUS = 0;
    /** The power, in two halves: menu data only carries 16 bits a slot. */
    static final int DATA_POWER_LOW = 1;
    static final int DATA_POWER_HIGH = 2;
    static final int DATA_CHUNKS = 3;
    static final int DATA_COUNT = 4;

    private final @Nullable NetworkTetherBlockEntity tether;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    /** Server side. */
    public NetworkTetherMenu(int containerId, Inventory inventory, NetworkTetherBlockEntity tether, ContainerLevelAccess access) {
        this(containerId, inventory, tether, access, tether, data(tether));
    }

    /** Client side. */
    public NetworkTetherMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(1), ContainerLevelAccess.NULL, null, new SimpleContainerData(DATA_COUNT));
    }

    private NetworkTetherMenu(int containerId, Inventory inventory, Container container, ContainerLevelAccess access,
            @Nullable NetworkTetherBlockEntity tether, ContainerData data) {
        super(JasmMenus.NETWORK_TETHER.get(), containerId);
        this.tether = tether;
        this.access = access;
        this.data = data;
        addSlot(new OperatorSlot(container, NetworkTetherBlockEntity.OPERATOR, OPERATOR_X, SLOT_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    private static ContainerData data(NetworkTetherBlockEntity tether) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_STATUS -> tether.status().ordinal();
                    case DATA_POWER_LOW -> tether.powerUse() & 0xFFFF;
                    case DATA_POWER_HIGH -> tether.powerUse() >>> 16;
                    case DATA_CHUNKS -> tether.chunksHeld();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {}

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    // --- what the screen reads ---

    public TetherStatus status() {
        TetherStatus[] all = TetherStatus.values();
        int index = data.get(DATA_STATUS);
        return index >= 0 && index < all.length ? all[index] : TetherStatus.EMPTY;
    }

    /** FE a tick the operator is paying for the chunks right now. */
    public int powerUse() {
        return data.get(DATA_POWER_HIGH) << 16 | data.get(DATA_POWER_LOW) & 0xFFFF;
    }

    public int chunks() {
        return data.get(DATA_CHUNKS);
    }

    public ItemStack operator() {
        return slots.get(NetworkTetherBlockEntity.OPERATOR).getItem();
    }

    /** Shift-click: the operator goes to the inventory; a Nibbling or Byteling goes to the desk. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        if (index < 1) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, 1, false)) {
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
    public boolean stillValid(Player player) {
        return tether == null || !tether.isRemoved() && stillValid(access, player, JasmBlocks.NETWORK_TETHER.get()) && MachineAccess.canUse(tether, player);
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return tether == null ? null : tether.getBlockPos();
    }

    /** One critter, and only a Nibbling or Byteling. */
    private static final class OperatorSlot extends Slot {
        OperatorSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return NetworkTetherBlockEntity.accepts(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }
    }
}
