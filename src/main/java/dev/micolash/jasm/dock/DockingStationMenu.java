package dev.micolash.jasm.dock;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Docking Station's panel: the Deck slot, whose Deck it is, the lock, and the player's inventory. A Deck goes in by
 * clicking it into the slot or shift-clicking it from the inventory; it only comes out through the Take out key. The
 * server checks every one of those itself, and says no in the panel.
 */
public class DockingStationMenu extends AbstractContainerMenu implements Notices.Board, MachineView {
    public static final int WIDTH = 176;
    public static final int DECK_X = (WIDTH - 16) / 2;
    public static final int DECK_Y = 22;
    public static final int INVENTORY_X = (WIDTH - 9 * 18) / 2 + 1;
    public static final int INVENTORY_Y = 116;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_LOCKED = 3;
    /** What the player looking may do, worked out on the server for them: bits below. */
    static final int DATA_MAY = 4;
    static final int DATA_COUNT = 5;

    public static final int MAY_TAKE = 1;
    public static final int MAY_LOCK = 2;
    public static final int NETWORK_OWNER = 4;
    public static final int DECK_OWNER = 8;
    /** The docked Deck is paired on this network, so the network can use it. */
    public static final int LINKED = 16;

    private static final Identifier EMPTY_DECK = Jasm.id("container/empty_deck");

    private final @Nullable DockingStationBlockEntity dock;
    private final Player viewer;
    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final Notices.Shown notices = new Notices.Shown();
    /** Client: whose Deck it is, as the server last said. */
    private String deckOwnerName = "";
    /** Server: the name last sent. */
    private @Nullable String sentName;

    /** Server side. */
    public DockingStationMenu(int containerId, Inventory inventory, DockingStationBlockEntity dock, ContainerLevelAccess access) {
        this(containerId, inventory, dock, access, dock, data(dock, inventory.player));
    }

    /** Client side. */
    public DockingStationMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(1), ContainerLevelAccess.NULL, null, new SimpleContainerData(DATA_COUNT));
    }

    private DockingStationMenu(int containerId, Inventory inventory, Container container, ContainerLevelAccess access,
            @Nullable DockingStationBlockEntity dock, ContainerData data) {
        super(JasmMenus.DOCKING_STATION.get(), containerId);
        this.dock = dock;
        this.viewer = inventory.player;
        this.access = access;
        this.data = data;
        addSlot(new DockSlot(container, DECK_X, DECK_Y));
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

    private static ContainerData data(DockingStationBlockEntity dock, Player player) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_ENERGY_LOW -> ContainerWords.low(dock.energy().getAmountAsInt());
                    case DATA_ENERGY_HIGH -> ContainerWords.high(dock.energy().getAmountAsInt());
                    case DATA_RUNNING -> dock.running() && !dock.stopped() ? 1 : 0;
                    case DATA_LOCKED -> dock.locked() ? 1 : 0;
                    case DATA_MAY -> (dock.takeOutRefusal(player) == DockRefusal.NONE ? MAY_TAKE : 0)
                            | (dock.lockRefusal(player) == DockRefusal.NONE ? MAY_LOCK : 0)
                            | (dock.isNetworkOwner(player) ? NETWORK_OWNER : 0)
                            | (dock.isDeckOwner(player) ? DECK_OWNER : 0)
                            | (dock.linkedHere() ? LINKED : 0);
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

    public ItemStack deck() {
        return slots.get(0).getItem();
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public int capacity() {
        return Tuning.DOCK_CAPACITY;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    public boolean locked() {
        return data.get(DATA_LOCKED) != 0;
    }

    public boolean may(int what) {
        return (data.get(DATA_MAY) & what) != 0;
    }

    public String deckOwnerName() {
        return deckOwnerName;
    }

    public void setDeckOwnerName(String name) {
        deckOwnerName = name;
    }

    @Override
    public Notices.Shown notices() {
        return notices;
    }

    // --- what the player asks for ---

    /** A key in the panel. {@code expected} is the Deck the panel showed. Every request is checked again here. */
    void act(ServerPlayer player, DockPayloads.Action action, @Nullable UUID expected) {
        if (dock == null) return;
        DockRefusal refusal = switch (action) {
            case TAKE_OUT -> dock.takeOut(player, expected);
            case LOCK -> dock.setLocked(player, true, expected);
            case UNLOCK -> dock.setLocked(player, false, expected);
        };
        if (refusal != DockRefusal.NONE) Notices.bad(player, Component.translatable(refusal.key()));
        broadcastChanges();
    }

    private void tryDock(Player player, ItemStack stack) {
        if (dock == null || !(player instanceof ServerPlayer serverPlayer)) return;
        DockRefusal refusal = dock.dock(serverPlayer, stack);
        if (refusal != DockRefusal.NONE) Notices.bad(player, Component.translatable(refusal.key()));
    }

    /**
     * The Deck slot never takes part in the game's own clicks: what is on the cursor is offered to the dock instead, which
     * takes only a Deck (nothing else, chargeable or not) and says why not in the panel.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (slotIndex == 0) {
            ItemStack carried = getCarried();
            if (input == ContainerInput.PICKUP && !carried.isEmpty() && deck().isEmpty()) tryDock(player, carried);
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /** Shift-click: a Deck from the inventory is offered to the dock. Nothing leaves the dock this way. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index <= 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot clicked = slots.get(index);
        ItemStack stack = clicked.getItem();
        if (DeckItem.isDeck(stack) && deck().isEmpty()) {
            tryDock(player, stack);
            clicked.setChanged();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return dock == null || !dock.isRemoved() && stillValid(access, player, JasmBlocks.DOCKING_STATION.get()) && dock.canOpen(player);
    }

    /** Tells the screen whose Deck it is, whenever that changes. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (dock == null) return;
        String now = dock.deckOwnerName();
        if (!now.equals(sentName) && viewer instanceof ServerPlayer serverPlayer && serverPlayer.connection.hasChannel(DockPayloads.Owner.TYPE)) {
            sentName = now;
            PacketDistributor.sendToPlayer(serverPlayer, new DockPayloads.Owner(containerId, now));
        }
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return dock == null ? null : dock.getBlockPos();
    }

    /** The Deck slot: shows the docked Deck, never moved by the game's own clicks. */
    private static final class DockSlot extends Slot {
        DockSlot(Container container, int x, int y) {
            super(container, DockingStationBlockEntity.SLOT, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public Identifier getNoItemIcon() {
            return EMPTY_DECK;
        }
    }

    /** The id of the Deck shown in the slot, sent back with each key press. */
    public @Nullable UUID shownDeck() {
        return deck().get(JasmComponents.DECK_ID.get());
    }
}
