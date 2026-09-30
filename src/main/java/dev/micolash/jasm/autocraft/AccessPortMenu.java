package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The port's power upgrade, player inventory, charge, job state and name. */
public class AccessPortMenu extends AbstractContainerMenu {
    public static final int MAX_NAME = 32;
    public static final int POWER_X = -24;
    public static final int POWER_Y = 24;
    public static final int INVENTORY_Y = 108;
    public static final int PANEL_X = -30;
    public static final int PANEL_Y = 18;
    public static final int PANEL_SIZE = 28;
    private static final int INVENTORY_START = 1;
    private static final int HOTBAR_START = INVENTORY_START + 27;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_LOCKED = 3;
    static final int DATA_COUNT = 4;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable AccessPortBlockEntity port;
    /** Client side: the name when the screen opened, and the machine's. */
    private final String label;
    private final Component machine;

    /** Server side. */
    public AccessPortMenu(int containerId, Inventory inventory, AccessPortBlockEntity port, ContainerLevelAccess access) {
        this(containerId, inventory, port.data(), access, port, port.label(), Component.empty());
    }

    /** Client side. */
    public static AccessPortMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        buf.readBlockPos();
        String label = buf.readUtf(MAX_NAME);
        Component machine = ComponentSerialization.STREAM_CODEC.decode(buf);
        return new AccessPortMenu(containerId, inventory, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, label, machine);
    }

    private AccessPortMenu(int containerId, Inventory inventory, ContainerData data, ContainerLevelAccess access, @Nullable AccessPortBlockEntity port,
            String label, Component machine) {
        super(JasmMenus.ACCESS_PORT.get(), containerId);
        this.data = data;
        this.access = access;
        this.port = port;
        this.label = label;
        this.machine = machine;
        addDataSlots(data);
        addSlot(new Slot(port == null ? new SimpleContainer(1) : port, port == null ? 0 : AccessPortBlockEntity.POWER_SLOT, POWER_X, POWER_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.POWER_UPGRADE.get()); }
            @Override public int getMaxStackSize() { return 1; }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58));
        }
    }

    public @Nullable AccessPortBlockEntity port() {
        return port;
    }

    public String label() {
        return label;
    }

    public Component machine() {
        return machine;
    }

    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    public int capacity() {
        return AccessPortBlockEntity.CAPACITY;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** Whether a job has sets in the machine right now. */
    public boolean locked() {
        return data.get(DATA_LOCKED) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        return port == null || port.installed() && player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) <= 64 && MachineAccess.canUse(port, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem() || !clicked.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved;
        if (index == 0) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_START + 9, false)
                    || moveItemStackTo(stack, INVENTORY_START, HOTBAR_START, false);
        } else if (stack.is(JasmItems.POWER_UPGRADE.get())) {
            moved = moveItemStackTo(stack, 0, 1, false);
        } else if (index < HOTBAR_START) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_START + 9, false);
        } else {
            moved = moveItemStackTo(stack, INVENTORY_START, HOTBAR_START, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) clicked.setByPlayer(ItemStack.EMPTY);
        else clicked.setChanged();
        return before;
    }

    /** Server side: whether the port this menu shows stands at {@code pos}. */
    boolean shows(BlockPos pos) {
        return port != null && port.getBlockPos().equals(pos);
    }
}
