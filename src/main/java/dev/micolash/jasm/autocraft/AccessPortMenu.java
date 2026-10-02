package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.LinkWindowCover;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The port's upgrades, item buffer, Deck link, status and player inventory. */
public class AccessPortMenu extends AbstractContainerMenu implements MachineView {
    public static final int MAX_NAME = 32;
    public static final int WIDTH = PortUpgradeLayout.MAIN_WIDTH;
    /** As tall as the Input Port: the inventory sits at the same height. */
    public static final int INVENTORY_Y = 180;
    /** Side keys in the right-hand column: the Deck Link and blocking mode. */
    public static final int SIDE_KEYS = 2;
    public static final int BUFFER_X = (WIDTH - 8 * 18) / 2;
    public static final int BUFFER_Y = 140;
    public static final int SLOT_BUFFER = 1;
    public static final int SLOT_DECK_IN = SLOT_BUFFER + AccessPortBlockEntity.BUFFER_SLOTS;
    public static final int SLOT_DECK_OUT = SLOT_DECK_IN + 1;
    public static final int SLOT_SPEED = SLOT_DECK_OUT + 1;
    public static final int RESET_DECK = 0;
    public static final int TOGGLE_BLOCKING = 1;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_LOCKED = 3;
    static final int DATA_DEFAULT_DECK = 4;
    static final int DATA_LINKED = 5;
    static final int DATA_BLOCKING = 6;
    public static final int DATA_COUNT = 7;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable AccessPortBlockEntity port;
    /** Client side: the name when the screen opened. */
    private final String label;
    private List<MachineView> machines;
    private final Player player;
    private final int inventoryStart;
    private final int hotbarStart;
    /** Client side: where the Deck Link window lies over the screen. */
    private final LinkWindowCover linkCover = new LinkWindowCover();
    private String linkedPlayer = "";
    /** Server side: the name last sent, so it goes again only when it changes. */
    private final String[] lastPlayer = {null};

    /** Server side. */
    public AccessPortMenu(int containerId, Inventory inventory, AccessPortBlockEntity port, ContainerLevelAccess access) {
        this(containerId, inventory, port.data(), access, port, port.label(), connectedMachines(port));
    }

    /** Client side. */
    public static AccessPortMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        buf.readBlockPos();
        String label = buf.readUtf(MAX_NAME);
        List<MachineView> machines = MachineView.STREAM_CODEC.apply(ByteBufCodecs.list(6)).decode(buf);
        return new AccessPortMenu(containerId, inventory, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, label, machines);
    }

    private AccessPortMenu(int containerId, Inventory inventory, ContainerData data, ContainerLevelAccess access, @Nullable AccessPortBlockEntity port,
            String label, List<MachineView> machines) {
        super(JasmMenus.ACCESS_PORT.get(), containerId);
        this.data = data;
        this.access = access;
        this.port = port;
        this.label = label;
        this.machines = List.copyOf(machines);
        this.player = inventory.player;
        this.inventoryStart = SLOT_SPEED + PortOperations.UPGRADE_SLOTS;
        this.hotbarStart = inventoryStart + 27;
        addDataSlots(data);
        var container = port == null ? new SimpleContainer(AccessPortBlockEntity.INVENTORY_SIZE) : port;
        addSlot(new Slot(container, AccessPortBlockEntity.POWER_SLOT, PortUpgradeLayout.SLOT_X, PortUpgradeLayout.powerY(SIDE_KEYS)) {
            @Override public boolean isActive() { return !linkCover.covers(this); }
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.POWER_UPGRADE.get()); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public net.minecraft.resources.Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
        });
        for (int col = 0; col < AccessPortBlockEntity.BUFFER_SLOTS; col++) {
            addSlot(new Slot(container, col, BUFFER_X + col * 18, BUFFER_Y) {
                @Override public boolean isActive() { return !linkCover.covers(this); }
            });
        }
        addSlot(new Slot(container, AccessPortBlockEntity.DECK_IN, 0, 0) {
            @Override public boolean isActive() { return port != null || linkCover.open(); }
            @Override public boolean mayPlace(ItemStack stack) { return DeckItem.isDeck(stack); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public net.minecraft.resources.Identifier getNoItemIcon() { return Jasm.id("container/empty_deck"); }
        });
        addSlot(new Slot(container, AccessPortBlockEntity.DECK_OUT, 0, 0) {
            @Override public boolean isActive() { return port != null || linkCover.open(); }
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            addSlot(new Slot(container, AccessPortBlockEntity.SPEED_START + i, PortUpgradeLayout.SLOT_X, PortUpgradeLayout.speedY(SIDE_KEYS, i)) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.SPEED_UPGRADE.get()); }
                @Override public int getMaxStackSize() { return 1; }
                @Override public net.minecraft.resources.Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, (WIDTH - 162) / 2 + col * 18, inventoryY() + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, (WIDTH - 162) / 2 + col * 18, inventoryY() + 58));
        }
    }

    public int inventoryY() { return INVENTORY_Y; }
    public LinkWindowCover linkCover() { return linkCover; }
    public boolean defaultDeck() { return data.get(DATA_DEFAULT_DECK) != 0; }
    public boolean deckLinked() { return data.get(DATA_LINKED) != 0; }
    public boolean blockingMode() { return data.get(DATA_BLOCKING) != 0; }
    public boolean canResetDeck() { return !defaultDeck() && getSlot(SLOT_DECK_IN).getItem().isEmpty(); }
    public int rate() {
        int upgrades = 0;
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) if (getSlot(SLOT_SPEED + i).getItem().is(JasmItems.SPEED_UPGRADE.get())) upgrades++;
        return PortOperations.itemsPerOperation(upgrades);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (port == null || !stillValid(player)) return false;
        if (id == RESET_DECK) return port.resetDeck(player);
        if (id == TOGGLE_BLOCKING) {
            port.setBlockingMode(!port.blockingMode());
            return true;
        }
        return false;
    }

    @Override
    public void clicked(int index, int button, ContainerInput input, Player player) {
        ItemStack before = getSlot(SLOT_DECK_IN).getItem().copy();
        super.clicked(index, button, input, player);
        if (port != null) {
            if (!ItemStack.matches(before, getSlot(SLOT_DECK_IN).getItem())) port.queueDeckLink(player);
            port.processDeckLink();
        }
    }

    public @Nullable AccessPortBlockEntity port() {
        return port;
    }

    public String label() {
        return label;
    }

    public record MachineView(Component name, ItemStack icon) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MachineView> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, MachineView::name,
                ItemStack.OPTIONAL_STREAM_CODEC, MachineView::icon,
                MachineView::new);
    }

    static List<MachineView> connectedMachines(AccessPortBlockEntity port) {
        if (port.getLevel() == null) return List.of();
        return port.machineSides().stream().map(side -> {
            var pos = port.getBlockPos().relative(side);
            return new MachineView(Machines.blockName(port.getLevel(), pos), new ItemStack(port.getLevel().getBlockState(pos).getBlock()));
        }).toList();
    }

    public List<MachineView> machines() { return machines; }

    public void setMachines(List<MachineView> machines) { this.machines = List.copyOf(machines); }

    /** Whose Deck the port delivers to; on the client, what the server last said. */
    public String linkedPlayer() { return linkedPlayer; }

    public void setLinkedPlayer(String name) { linkedPlayer = name; }

    /** Sends whose Deck the port delivers to whenever it changes, for the Deck Link window. */
    public static void sendLinkedPlayer(ServerPlayer player, int containerId, AccessPortBlockEntity port, String[] last) {
        String now = port.linkedPlayerName();
        if (!now.equals(last[0]) && player.connection.hasChannel(CraftPayloads.PortLinkedPlayer.TYPE)) {
            last[0] = now;
            PacketDistributor.sendToPlayer(player, new CraftPayloads.PortLinkedPlayer(containerId, now));
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (port == null || !(player instanceof ServerPlayer serverPlayer) || serverPlayer.level().getGameTime() % 10 != 0) return;
        sendLinkedPlayer(serverPlayer, containerId, port, lastPlayer);
        List<MachineView> now = connectedMachines(port);
        boolean same = now.size() == machines.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i).name().equals(machines.get(i).name()) && ItemStack.matches(now.get(i).icon(), machines.get(i).icon());
        }
        if (!same) {
            machines = now;
            if (serverPlayer.connection.hasChannel(CraftPayloads.PortMachines.TYPE)) {
                PacketDistributor.sendToPlayer(serverPlayer, new CraftPayloads.PortMachines(containerId, now));
            }
        }
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
        if (index < inventoryStart) {
            moved = moveItemStackTo(stack, hotbarStart, hotbarStart + 9, false)
                    || moveItemStackTo(stack, inventoryStart, hotbarStart, false);
        } else if (stack.is(JasmItems.POWER_UPGRADE.get())) {
            moved = moveItemStackTo(stack, 0, 1, false);
        } else if (stack.is(JasmItems.SPEED_UPGRADE.get())) {
            moved = false;
            for (int i = 0; i < PortOperations.UPGRADE_SLOTS && !stack.isEmpty(); i++) {
                if (!moveItemStackTo(stack, SLOT_SPEED, inventoryStart, false)) break;
                moved = true;
            }
        } else if (DeckItem.isDeck(stack)) {
            moved = moveItemStackTo(stack, SLOT_DECK_IN, SLOT_DECK_OUT, false);
        } else {
            moved = moveItemStackTo(stack, SLOT_BUFFER, SLOT_DECK_IN, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) clicked.setByPlayer(ItemStack.EMPTY);
        else clicked.setChanged();
        if (port != null) {
            if (index >= inventoryStart && DeckItem.isDeck(before)) port.queueDeckLink(player);
            port.processDeckLink();
        }
        return before;
    }

    /** Server side: whether the port this menu shows stands at {@code pos}. */
    boolean shows(BlockPos pos) {
        return port != null && port.getBlockPos().equals(pos);
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return port == null ? null : port.getBlockPos();
    }
}
