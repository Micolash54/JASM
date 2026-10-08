package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import dev.micolash.jasm.transfer.TransferPortMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/** A bay's panel: the 3×3 grid, the upgrade column, the filter, then the player's inventory and hotbar. The tank is drawn, not a slot. */
public class BayMenu extends AbstractContainerMenu implements MachineView {
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    /** The main panel's width, the ports' own; the upgrade column hangs past its right edge, as on the ports. */
    public static final int WIDTH = PortUpgradeLayout.MAIN_WIDTH;
    public static final int UPGRADE_X = WIDTH + 2;
    public static final int UPGRADE_Y = 29;
    public static final int INVENTORY_X = (WIDTH - 162) / 2;
    public static final int FILTER_Y = 76;
    // a short game window gets fewer
    public static final int FILTER_ROWS = 2;
    public static final int BUTTON_REDSTONE = 0;
    public static final int BUTTON_MODE = 1;

    static final int DATA_STATUS = 0;
    static final int DATA_ENERGY_LOW = 1;
    static final int DATA_ENERGY_HIGH = 2;
    static final int DATA_CAPACITY_LOW = 3;
    static final int DATA_CAPACITY_HIGH = 4;
    static final int DATA_FLUID = 5;
    static final int DATA_FLUID_LOW = 6;
    static final int DATA_FLUID_HIGH = 7;
    static final int DATA_TANK_LOW = 8;
    static final int DATA_TANK_HIGH = 9;
    static final int DATA_FLAGS = 10;
    static final int DATA_CYCLE = 11;
    static final int DATA_COUNT = 12;

    static final int FLAG_REDSTONE_UPGRADE = 1;
    static final int FLAG_DROP = 2;
    /** The redstone mode sits in the flags from this bit up. */
    static final int REDSTONE_SHIFT = 4;

    private static final int HOTBAR_START = BayBlockEntity.SLOTS + 27;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final BayKind kind;
    private final ContainerData data;
    private final @Nullable BayBlockEntity bay;
    private final @Nullable BlockPos pos;
    private WaferSettings filter;

    /** Server side. */
    public BayMenu(BayKind kind, int containerId, Inventory inventory, BayBlockEntity bay) {
        this(kind, containerId, inventory, bay, serverData(bay), bay, bay.getBlockPos(), bay.filter());
    }

    public static void writeOpening(RegistryFriendlyByteBuf buf, BayBlockEntity bay) {
        buf.writeBlockPos(bay.getBlockPos());
        WaferSettings.STREAM_CODEC.encode(buf, bay.filter());
    }

    /** Where the player's inventory starts: under a filter of {@code rows} rows, or under its heading while it is folded away. */
    public static int inventoryY(boolean collapsed, int rows) {
        return collapsed ? FILTER_Y + 29 : FILTER_Y + TransferPortMenu.filterHeight(rows) + 15;
    }

    /** Client side. */
    public static BayMenu client(BayKind kind, int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        WaferSettings filter = WaferSettings.STREAM_CODEC.decode(buf);
        Container container = new SimpleContainer(BayBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return BayBlockEntity.accepts(kind, slot, stack, other -> false);
            }
        };
        return new BayMenu(kind, containerId, inventory, container, new SimpleContainerData(DATA_COUNT), null, pos, filter);
    }

    private BayMenu(BayKind kind, int containerId, Inventory inventory, Container container, ContainerData data,
            @Nullable BayBlockEntity bay, @Nullable BlockPos pos, WaferSettings filter) {
        super(kind.menu(), containerId);
        this.kind = kind;
        this.data = data;
        this.bay = bay;
        this.pos = pos;
        this.filter = filter;
        int inventoryY = inventoryY(false, FILTER_ROWS);
        for (int i = 0; i < BayBlockEntity.GRID; i++) {
            addSlot(new GridSlot(container, i, GRID_X + i % 3 * 18, GRID_Y + i / 3 * 18, kind.takesIn()));
        }
        for (int i = 0; i < BayBlockEntity.UPGRADES; i++) {
            addSlot(new UpgradeSlot(container, BayBlockEntity.UPGRADE_START + i, UPGRADE_X, UPGRADE_Y + i * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, INVENTORY_X + column * 18, inventoryY + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, inventoryY + 58));
        }
        addDataSlots(data);
    }

    private static ContainerData serverData(BayBlockEntity bay) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                FluidResource fluid = bay.tank().getResource(0);
                int amount = (int) bay.tank().getAmountAsLong(0);
                int tank = (int) bay.tank().getCapacityAsLong(0, FluidResource.EMPTY);
                return switch (index) {
                    case DATA_STATUS -> bay.status().ordinal();
                    case DATA_ENERGY_LOW -> ContainerWords.low(bay.energy().getAmountAsInt());
                    case DATA_ENERGY_HIGH -> ContainerWords.high(bay.energy().getAmountAsInt());
                    case DATA_CAPACITY_LOW -> ContainerWords.low(bay.capacity());
                    case DATA_CAPACITY_HIGH -> ContainerWords.high(bay.capacity());
                    case DATA_FLUID -> fluid.isEmpty() ? -1 : BuiltInRegistries.FLUID.getId(fluid.getFluid());
                    case DATA_FLUID_LOW -> ContainerWords.low(amount);
                    case DATA_FLUID_HIGH -> ContainerWords.high(amount);
                    case DATA_TANK_LOW -> ContainerWords.low(tank);
                    case DATA_TANK_HIGH -> ContainerWords.high(tank);
                    case DATA_FLAGS -> (bay.hasRedstoneUpgrade() ? FLAG_REDSTONE_UPGRADE : 0)
                            | (bay instanceof DeploymentBayBlockEntity deploy && deploy.mode() == DeployMode.DROP ? FLAG_DROP : 0)
                            | bay.redstone().ordinal() << REDSTONE_SHIFT;
                    case DATA_CYCLE -> bay.cycleTicks();
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

    public BayKind kind() {
        return kind;
    }

    public @Nullable BlockPos bayPos() {
        return pos;
    }

    public BayStatus status() {
        return BayStatus.byId(data.get(DATA_STATUS));
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public int capacity() {
        return ContainerWords.join(data.get(DATA_CAPACITY_HIGH), data.get(DATA_CAPACITY_LOW));
    }

    public int fluidAmount() {
        return ContainerWords.join(data.get(DATA_FLUID_HIGH), data.get(DATA_FLUID_LOW));
    }

    public int tankCapacity() {
        return ContainerWords.join(data.get(DATA_TANK_HIGH), data.get(DATA_TANK_LOW));
    }

    public FluidResource fluid() {
        int id = data.get(DATA_FLUID);
        return id < 0 ? FluidResource.EMPTY : FluidResource.of(BuiltInRegistries.FLUID.byId(id));
    }

    public boolean hasRedstoneUpgrade() {
        return (data.get(DATA_FLAGS) & FLAG_REDSTONE_UPGRADE) != 0;
    }

    public DeployMode mode() {
        return (data.get(DATA_FLAGS) & FLAG_DROP) != 0 ? DeployMode.DROP : DeployMode.PLACE;
    }

    public BayRedstone redstone() {
        return BayRedstone.byId(data.get(DATA_FLAGS) >> REDSTONE_SHIFT);
    }

    public int cycleTicks() {
        return data.get(DATA_CYCLE);
    }

    public WaferSettings filter() {
        return bay != null ? bay.filter() : filter;
    }

    // a row naming nothing known is turned away, unless the bay already had it (its mod was removed since)
    public boolean configureFilter(WaferSettings wanted) {
        WaferSettings previous = filter();
        if (wanted.rules().stream().anyMatch(rule -> !rule.valid()
                && previous.rules().stream().noneMatch(old -> old.mode() == rule.mode() && old.value().equals(rule.value()))))
            return false;
        filter = wanted;
        if (bay != null) bay.setFilter(wanted);
        return true;
    }

    // only on the player's screen, the server never looks at where slots sit
    public void layout(boolean collapsed, int rows) {
        int top = inventoryY(collapsed, rows);
        for (int i = 0; i < 36; i++) {
            Slot slot = slots.get(BayBlockEntity.SLOTS + i);
            slot.y = i < 27 ? top + i / 9 * 18 : top + 58;
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (bay == null) return false;
        if (id == BUTTON_REDSTONE && bay.hasRedstoneUpgrade()) {
            bay.setRedstone(bay.redstone().next());
            return true;
        }
        if (id == BUTTON_MODE && bay instanceof DeploymentBayBlockEntity deploy) {
            deploy.toggleMode();
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return bay == null || Container.stillValidBlockEntity(bay, player);
    }

    /** Shift-click: upgrades to the upgrade column, anything else into a Deployment Bay's grid; out of the bay to the hotbar first. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        boolean moved;
        if (index < BayBlockEntity.SLOTS) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false) || moveItemStackTo(stack, BayBlockEntity.SLOTS, HOTBAR_START, false);
        } else if (stack.is(JasmItems.SPEED_UPGRADE.get()) || stack.is(JasmItems.REDSTONE_UPGRADE.get())) {
            // Each upgrade slot holds one, and one move fills one empty slot.
            moved = false;
            while (!stack.isEmpty() && moveItemStackTo(stack, BayBlockEntity.UPGRADE_START, BayBlockEntity.SLOTS, false)) moved = true;
        } else {
            moved = kind.takesIn() && moveItemStackTo(stack, 0, BayBlockEntity.GRID, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) clicked.setByPlayer(ItemStack.EMPTY);
        else clicked.setChanged();
        return before;
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return bay == null ? null : pos;
    }

    private static final class GridSlot extends Slot {
        private final boolean takesIn;

        GridSlot(Container container, int index, int x, int y, boolean takesIn) {
            super(container, index, x, y);
            this.takesIn = takesIn;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return takesIn;
        }
    }

    private static final class UpgradeSlot extends Slot {
        UpgradeSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public Identifier getNoItemIcon() {
            return Jasm.id("container/empty_upgrade");
        }
    }
}
