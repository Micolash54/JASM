package dev.micolash.jasm.battery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Battery's menu: readings of the whole battery the clicked block belongs to, the battery's one charging slot, then
 * the player's inventory (27 slots) and hotbar (9). Synced numbers travel as 16-bit words, so each reading goes as four of them.
 */
public class BatteryMenu extends AbstractContainerMenu {
    private static final Identifier EMPTY_BOLT = Jasm.id("container/empty_bolt");
    /** The charging slot, centred at the top of the panel. */
    public static final int SLOT_X = 80;
    public static final int SLOT_Y = 20;
    public static final int INVENTORY_Y = 112;
    private static final int WORDS = 4;
    private static final int STORED = 0;
    private static final int CAPACITY = 1;
    private static final int IN = 2;
    private static final int OUT = 3;
    private static final int BLOCKS = 4;
    private static final int MAX_BLOCKS = 5;
    static final int DATA_COUNT = 6 * WORDS;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable BatteryBlockEntity battery;
    private final Container slot;

    /** Server side. */
    public BatteryMenu(int containerId, Inventory inventory, BatteryBlockEntity battery, ContainerLevelAccess access) {
        this(containerId, inventory, battery, battery.chargingSlot(), access, new Readings(battery));
    }

    /** Client side. */
    public BatteryMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, CreativeBatteryMenu.chargingSlot(() -> {}), ContainerLevelAccess.NULL,
                new SimpleContainerData(DATA_COUNT));
    }

    private BatteryMenu(int containerId, Inventory inventory, @Nullable BatteryBlockEntity battery, Container slot,
            ContainerLevelAccess access, ContainerData data) {
        super(JasmMenus.BATTERY.get(), containerId);
        this.battery = battery;
        this.slot = slot;
        this.access = access;
        this.data = data;
        addDataSlots(data);
        addSlot(new ChargingSlot(slot, SLOT_X, SLOT_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
    }

    /** The item in the charging slot. */
    public ItemStack charging() {
        return slot.getItem(0);
    }

    private long read(int reading) {
        long value = 0;
        for (int word = WORDS - 1; word >= 0; word--) {
            value = value << 16 | data.get(reading * WORDS + word) & 0xFFFF;
        }
        return value;
    }

    /** FE the whole battery holds. */
    public long stored() {
        return read(STORED);
    }

    public long capacity() {
        return read(CAPACITY);
    }

    /** FE a tick going in and coming out, averaged over the last second. */
    public long in() {
        return read(IN);
    }

    public long out() {
        return read(OUT);
    }

    /** Blocks sharing the battery. */
    public long blocks() {
        return read(BLOCKS);
    }

    /** Most blocks one battery may have in this world. */
    public long maxBlocks() {
        return read(MAX_BLOCKS);
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) == battery
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64, true);
    }

    /**
     * Shift-click moves the charging item to the leftmost free hotbar slot, or failing that the first free inventory
     * slot from the top left; a chargeable item from the inventory goes into the charging slot.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved = index == 0
                ? moveItemStackTo(stack, 28, 37, false) || moveItemStackTo(stack, 1, 28, false)
                : CreativeBatteryMenu.canCharge(stack) && moveItemStackTo(stack, 0, 1, false);
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

    /** Takes what the battery's slot takes: an item that holds power, and nothing once the clicked block is gone. */
    private static final class ChargingSlot extends Slot {
        ChargingSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        /** Shows a faint lightning bolt while empty. */
        @Override
        public Identifier getNoItemIcon() {
            return EMPTY_BOLT;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(0, stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** Read from the battery's last tick, so a synced screen costs no extra work. */
    private static final class Readings implements ContainerData {
        private final BatteryBlockEntity battery;

        Readings(BatteryBlockEntity battery) {
            this.battery = battery;
        }

        @Override
        public int get(int index) {
            if (battery.isRemoved() || !(battery.getLevel() instanceof ServerLevel level)) {
                return 0;
            }
            BatteryGroup group = battery.group(level);
            long value = switch (index / WORDS) {
                case STORED -> group.stored();
                case CAPACITY -> group.energy().getCapacityAsLong();
                case IN -> group.flowIn();
                case OUT -> group.flowOut();
                case BLOCKS -> group.size();
                default -> BatteryBlock.maxBlocks();
            };
            return (int) (value >>> 16 * (index % WORDS)) & 0xFFFF;
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
