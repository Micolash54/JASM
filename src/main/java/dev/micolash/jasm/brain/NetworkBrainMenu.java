package dev.micolash.jasm.brain;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BrainSize;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.registry.JasmTags;
import net.minecraft.core.BlockPos;
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

/** The brain's menu: the chip slot, then the player's inventory and hotbar. */
public class NetworkBrainMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = 176;
    public static final int CHIP_X = 8;
    public static final int CHIP_Y = 84;
    public static final int INVENTORY_Y = 118;
    private static final Identifier EMPTY_CHIP = Jasm.id("container/empty_processor");

    static final int DATA_LEVEL = 0;
    static final int DATA_PERCENT = 1;
    static final int DATA_COUNT = 2;
    static final int DATA_LIMIT = 3;
    static final int DATA_SIZE = 4;
    static final int DATA_STATUS = 5;
    static final int DATA_ENERGY_LOW = 6;
    static final int DATA_ENERGY_HIGH = 7;
    static final int DATA_SLOTS = 8;

    private static final int HOTBAR_START = 1 + 27;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;
    private final @Nullable NetworkBrainBlockEntity brain;

    /** Server side. */
    public NetworkBrainMenu(int containerId, Inventory inventory, NetworkBrainBlockEntity brain, ContainerData data, ContainerLevelAccess access,
            Block block) {
        this(containerId, inventory, brain, data, access, block, brain);
    }

    /** Client side. */
    public NetworkBrainMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(NetworkBrainBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return stack.is(JasmTags.TYPED_CHIPS);
            }
        }, new SimpleContainerData(DATA_SLOTS), ContainerLevelAccess.NULL, null, null);
    }

    private NetworkBrainMenu(int containerId, Inventory inventory, Container container, ContainerData data, ContainerLevelAccess access,
            @Nullable Block block, @Nullable NetworkBrainBlockEntity brain) {
        super(JasmMenus.NETWORK_BRAIN.get(), containerId);
        this.data = data;
        this.access = access;
        this.block = block;
        this.brain = brain;
        addSlot(new Slot(container, NetworkBrainBlockEntity.SLOT, CHIP_X, CHIP_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return container.canPlaceItem(getContainerSlot(), stack);
            }

            /** A faint chip while empty, so the slot says what it is for. */
            @Override
            public Identifier getNoItemIcon() {
                return EMPTY_CHIP;
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

    public int level() {
        return data.get(DATA_LEVEL);
    }

    public int percent() {
        return data.get(DATA_PERCENT);
    }

    public int machineCount() {
        return data.get(DATA_COUNT);
    }

    public int machineLimit() {
        return data.get(DATA_LIMIT);
    }

    public BrainSize size() {
        BrainSize[] sizes = BrainSize.values();
        int index = data.get(DATA_SIZE);
        return index >= 0 && index < sizes.length ? sizes[index] : BrainSize.SINGLE;
    }

    public BrainStatus status() {
        BrainStatus[] statuses = BrainStatus.values();
        int index = data.get(DATA_STATUS);
        return index >= 0 && index < statuses.length ? statuses[index] : BrainStatus.NO_POWER;
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public int capacity() {
        return NetworkBrainBlockEntity.CAPACITY;
    }

    @Override
    public boolean stillValid(Player player) {
        return block == null || stillValid(access, player, block);
    }

    /** Shift-click: typed chips to the slot; out of the brain to the hotbar, then the inventory. */
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
            moved = slots.getFirst().mayPlace(stack) && moveItemStackTo(stack, 0, 1, false);
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
        return brain == null ? null : brain.getBlockPos();
    }
}
