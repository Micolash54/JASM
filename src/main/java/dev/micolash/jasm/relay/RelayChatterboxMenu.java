package dev.micolash.jasm.relay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
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
 * The Relay Chatterbox's panel: the frequency, the operator and Dimension Upgrade slots, where the pair stands,
 * and the player's inventory. Only players trusted on the network may open it or change anything; the server checks
 * that on every click and every frequency change.
 */
public class RelayChatterboxMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = 176;
    public static final int FREQUENCY_Y = 20;
    public static final int SLOTS_Y = 44;
    public static final int OPERATOR_X = 8;
    public static final int UPGRADE_X = WIDTH - 8 - 16;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 107;

    static final int DATA_FREQUENCY = 0;
    static final int DATA_STATUS = 1;
    static final int DATA_POWER = 2;
    static final int DATA_COUNT = 3;

    private static final Identifier EMPTY_UPGRADE = Jasm.id("container/empty_upgrade");

    private final @Nullable RelayChatterboxBlockEntity relay;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    /** Server side. */
    public RelayChatterboxMenu(int containerId, Inventory inventory, RelayChatterboxBlockEntity relay, ContainerLevelAccess access) {
        this(containerId, inventory, relay, access, relay, data(relay));
    }

    /** Client side. */
    public RelayChatterboxMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(2), ContainerLevelAccess.NULL, null, new SimpleContainerData(DATA_COUNT));
    }

    private RelayChatterboxMenu(int containerId, Inventory inventory, Container container, ContainerLevelAccess access,
            @Nullable RelayChatterboxBlockEntity relay, ContainerData data) {
        super(JasmMenus.RELAY_CHATTERBOX.get(), containerId);
        this.relay = relay;
        this.access = access;
        this.data = data;
        addSlot(new RelaySlot(container, RelayChatterboxBlockEntity.OPERATOR, OPERATOR_X, SLOTS_Y));
        addSlot(new RelaySlot(container, RelayChatterboxBlockEntity.UPGRADE, UPGRADE_X, SLOTS_Y));
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

    private static ContainerData data(RelayChatterboxBlockEntity relay) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_FREQUENCY -> relay.frequency();
                    case DATA_STATUS -> relay.status().ordinal();
                    case DATA_POWER -> relay.powerUse();
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

    /** The frequency, or {@link RelayEnd#NO_FREQUENCY}. */
    public int frequency() {
        return data.get(DATA_FREQUENCY);
    }

    public RelayStatus status() {
        RelayStatus[] all = RelayStatus.values();
        int index = data.get(DATA_STATUS);
        return index >= 0 && index < all.length ? all[index] : RelayStatus.NO_FREQUENCY;
    }

    /** FE a tick this Chatterbox's operator is paying for the link right now. */
    public int powerUse() {
        return data.get(DATA_POWER);
    }

    public ItemStack operator() {
        return slots.get(RelayChatterboxBlockEntity.OPERATOR).getItem();
    }

    // --- what the player asks for ---

    /**
     * Sets the frequency to {@code to} (0 to 999, or none), if the panel showed {@code shown}: a request made about a
     * frequency that has changed since is dropped. Payloads check the panel and the player first.
     */
    void setFrequency(ServerPlayer player, int shown, int to) {
        if (relay == null || !stillValid(player) || shown != relay.frequency()) return;
        if (to != RelayEnd.NO_FREQUENCY && (to < 0 || to > RelayEnd.MAX_FREQUENCY)) return;
        relay.setFrequency(to);
        broadcastChanges();
    }

    /** Shift-click: the relay's items go to the inventory; an operator or Dimension Upgrade goes to its slot. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        if (index < 2) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, 2, false)) {
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
        return relay == null || !relay.isRemoved() && stillValid(access, player, JasmBlocks.RELAY_CHATTERBOX.get()) && MachineAccess.canUse(relay, player);
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return relay == null ? null : relay.getBlockPos();
    }

    /** One item, and only the one the slot is for. */
    private static final class RelaySlot extends Slot {
        RelaySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return RelayChatterboxBlockEntity.accepts(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return getContainerSlot() == RelayChatterboxBlockEntity.UPGRADE ? EMPTY_UPGRADE : null;
        }
    }
}
