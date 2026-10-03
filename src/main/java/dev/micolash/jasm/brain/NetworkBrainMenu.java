package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

/** The brain's menu: what its tower and network are doing, then the player's inventory and hotbar. */
public class NetworkBrainMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = 176;
    public static final int POWER_Y = 88;
    public static final int INVENTORY_Y = 118;

    static final int DATA_FLOORS = 0;
    static final int DATA_COUNT = 1;
    static final int DATA_LIMIT = 2;
    static final int DATA_STATUS = 3;
    static final int DATA_ENERGY_LOW = 4;
    static final int DATA_ENERGY_HIGH = 5;
    static final int DATA_SLOTS = 6;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable Block block;
    private final @Nullable NetworkBrainBlockEntity brain;

    /** Server side. */
    public NetworkBrainMenu(int containerId, Inventory inventory, NetworkBrainBlockEntity brain, ContainerData data, ContainerLevelAccess access,
            Block block) {
        this(containerId, inventory, data, access, block, brain);
    }

    /** Client side. */
    public NetworkBrainMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainerData(DATA_SLOTS), ContainerLevelAccess.NULL, null, null);
    }

    private NetworkBrainMenu(int containerId, Inventory inventory, ContainerData data, ContainerLevelAccess access, @Nullable Block block,
            @Nullable NetworkBrainBlockEntity brain) {
        super(JasmMenus.NETWORK_BRAIN.get(), containerId);
        this.data = data;
        this.access = access;
        this.block = block;
        this.brain = brain;
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

    /** "Lone brain", "1 floor" or "3 floors". */
    public static Component floorsText(int floors) {
        if (floors <= 0) {
            return Component.translatable("screen.jasm.brain.lone");
        }
        return floors == 1 ? Component.translatable("screen.jasm.brain.floor") : Component.translatable("screen.jasm.brain.floors", floors);
    }

    /** Floors in the brain's tower; 0 for a lone brain. */
    public int floors() {
        return data.get(DATA_FLOORS);
    }

    public int machineCount() {
        return data.get(DATA_COUNT);
    }

    public int machineLimit() {
        return data.get(DATA_LIMIT);
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

    /** Nothing to move into: the brain holds no items. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return brain == null ? null : brain.getBlockPos();
    }
}
