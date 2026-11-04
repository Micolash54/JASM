package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Rack Controller's menu: the player's inventory (27) and hotbar (9), and, sent apart, every Recipe Rack on the
 * controller's network that the player may use, with its name and its 16 slots. Cards are taken and put in by message,
 * straight in the real rack, so two players working on the same rack never see a card twice or lose one. Beside it,
 * the network's Access Ports, as on the Advanced Encoder.
 */
public class RackControllerMenu extends AbstractContainerMenu implements MachineView, PortList.Menu, Notices.Board {
    public static final int WIDTH = 176;
    /** The rack list: under the title and the search box, as wide as eight slots plus its scroll bar. */
    public static final int LIST_X = 8;
    public static final int LIST_Y = 36;
    /** The list's height at the smallest size; the player may make it taller (the screen grows with it). */
    public static final int LIST_HEIGHT = 100;
    /** From the list's bottom edge to the screen's: the inventory and its label. */
    private static final int BELOW_LIST = 16 + 58 + 18 + 6;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_DRAIN = 3;
    static final int DATA_COUNT = 4;

    /** Most racks in one message; a big network goes in several. */
    static final int PER_MESSAGE = 16;
    /** Ticks between two looks at the racks while the screen is open, unless the player just clicked one. */
    private static final int LOOK_EVERY = 10;

    /** One rack as the panel shows it: where it stands, its name (empty for the block's own), whether it has power, its cards. */
    public record RackView(NetworkNode at, String label, boolean powered, List<ItemStack> cards) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RackView> STREAM_CODEC = StreamCodec.composite(
                NetworkNode.STREAM_CODEC, RackView::at,
                ByteBufCodecs.stringUtf8(AccessPortMenu.MAX_NAME), RackView::label,
                ByteBufCodecs.BOOL, RackView::powered,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(RecipeRackBlockEntity.SLOTS)), RackView::cards,
                RackView::new);

        /** Named racks first, by name; then the rest by dimension and position. */
        public static final Comparator<RackView> ORDER = order(RackView::label, RackView::at);

        static RackView of(NetworkNode at, RecipeRackBlockEntity rack) {
            List<ItemStack> cards = new ArrayList<>(RecipeRackBlockEntity.SLOTS);
            for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) cards.add(rack.getItem(i).copy());
            return new RackView(at, rack.label(), rack.running(), List.copyOf(cards));
        }

        /** Whether the rack still looks like this. */
        boolean shows(RecipeRackBlockEntity rack) {
            if (!label.equals(rack.label()) || powered != rack.running()) return false;
            for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) {
                if (!ItemStack.matches(cards.get(i), rack.getItem(i))) return false;
            }
            return true;
        }

        public int filled() {
            int n = 0;
            for (ItemStack card : cards) if (!card.isEmpty()) n++;
            return n;
        }
    }

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable RackControllerBlockEntity controller;
    private final Player player;
    private int listHeight = LIST_HEIGHT;
    /** The racks the player last heard of; on the client, what the server sent. */
    private Map<NetworkNode, RackView> racks = new LinkedHashMap<>();
    /** Whether the whole network was loaded at the last look, so no rack can be missing from the list. */
    private boolean complete = true;
    /** Server side: whether the racks went out once yet, whether the player just clicked, and how many times they went. */
    private boolean synced;
    private boolean lookNow;
    private int sends;
    /** Client side: goes up with each message, so the screen knows to lay the list out again. */
    private int version;
    /** The port list beside the racks. */
    private final PortList portList = new PortList();
    /** Client side: the last message for this screen. */
    private final Notices.Shown notices = new Notices.Shown();

    /** Server side. */
    public RackControllerMenu(int containerId, Inventory inventory, RackControllerBlockEntity controller, ContainerLevelAccess access) {
        this(containerId, inventory, controller.data(), access, controller);
    }

    /** Client side. */
    public RackControllerMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    private RackControllerMenu(int containerId, Inventory inventory, ContainerData data, ContainerLevelAccess access,
            @Nullable RackControllerBlockEntity controller) {
        super(JasmMenus.RACK_CONTROLLER.get(), containerId);
        this.data = data;
        this.access = access;
        this.controller = controller;
        this.player = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, inventoryY() + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, inventoryY() + 58));
        }
        addDataSlots(data);
    }

    /** How tall the rack list is: the smallest size on the server, where it doesn't matter. */
    public int listHeight() {
        return listHeight;
    }

    public int inventoryY() {
        return LIST_Y + listHeight + 16;
    }

    public int screenHeight() {
        return LIST_Y + listHeight + BELOW_LIST;
    }

    /** Client side: the list is {@code height} tall, and the inventory moves to just under it. */
    public void layout(int height) {
        listHeight = height;
        for (Slot slot : slots) {
            int index = slot.getContainerSlot();
            slot.y = index < 9 ? inventoryY() + 58 : inventoryY() + (index - 9) / 9 * 18;
        }
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public int drain() {
        return data.get(DATA_DRAIN);
    }

    public int capacity() {
        return RackControllerBlockEntity.CAPACITY;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** The racks the panel shows, in no set order; on the server, what was last sent. */
    public Collection<RackView> racks() {
        return Collections.unmodifiableCollection(racks.values());
    }

    /** Whether every rack on the network could be looked at: false while part of it stands in unloaded chunks. */
    public boolean complete() {
        return complete;
    }

    public int version() {
        return version;
    }

    /** Server side: how many times the rack list changed and went to the player. */
    public int sends() {
        return sends;
    }

    // --- the racks ---

    private @Nullable CableNetwork network() {
        return controller != null && controller.getLevel() instanceof ServerLevel level ? Networks.at(level, controller.getBlockPos()) : null;
    }

    /** Named racks first, by name; then the rest by dimension and position. The panel and Shift-click both go by it. */
    private static <T> Comparator<T> order(Function<T, String> label, Function<T, NetworkNode> at) {
        return Comparator.comparing((T rack) -> label.apply(rack).isEmpty())
                .thenComparing(rack -> label.apply(rack).toLowerCase(Locale.ROOT))
                .thenComparing(rack -> at.apply(rack).dimension().identifier().toString())
                .thenComparing(rack -> at.apply(rack).pos());
    }

    private static NetworkNode node(RecipeRackBlockEntity rack) {
        return new NetworkNode(rack.getLevel().dimension(), rack.getBlockPos());
    }

    /**
     * The loaded racks on the controller's network that the player may use; none while the controller has no power.
     * Racks in other dimensions joined by a far link count too. Racks in unloaded chunks are never loaded to be shown.
     */
    private List<RecipeRackBlockEntity> usableRacks(Player player) {
        CableNetwork network = controller != null && controller.running() ? network() : null;
        if (network == null) return List.of();
        List<RecipeRackBlockEntity> found = new ArrayList<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            if (!rack.isRemoved() && rack.getLevel() != null && MachineAccess.canUse(rack, player)) found.add(rack);
        }
        return found;
    }

    /** The rack at {@code at} if it is one the panel may show to {@code player}. */
    private @Nullable RecipeRackBlockEntity rackAt(Player player, NetworkNode at) {
        for (RecipeRackBlockEntity rack : usableRacks(player)) {
            if (rack.getBlockPos().equals(at.pos()) && rack.getLevel().dimension().equals(at.dimension())) return rack;
        }
        return null;
    }

    /** Looks at the racks and sends those that changed since last time, and those gone; nothing when all is the same. */
    private void look(ServerPlayer player) {
        CableNetwork network = controller != null && controller.running() ? network() : null;
        boolean nowComplete = network == null || network.complete();
        Map<NetworkNode, RackView> now = new LinkedHashMap<>();
        List<RackView> changed = new ArrayList<>();
        for (RecipeRackBlockEntity rack : usableRacks(player)) {
            NetworkNode at = node(rack);
            RackView before = racks.get(at);
            if (before != null && before.shows(rack)) {
                now.put(at, before);
            } else {
                RackView view = RackView.of(at, rack);
                now.put(at, view);
                changed.add(view);
            }
        }
        List<NetworkNode> gone = new ArrayList<>();
        for (NetworkNode at : racks.keySet()) {
            if (!now.containsKey(at)) gone.add(at);
        }
        if (synced && changed.isEmpty() && gone.isEmpty() && nowComplete == complete) return;
        boolean reset = !synced;
        racks = now;
        complete = nowComplete;
        synced = true;
        sends++;
        if (!player.connection.hasChannel(RackPayloads.RackRows.TYPE)) return;
        // A first look sends at least one message, even for a network with no racks, so the screen knows.
        int i = 0;
        do {
            List<RackView> part = changed.subList(i, Math.min(changed.size(), i + PER_MESSAGE));
            PacketDistributor.sendToPlayer(player, new RackPayloads.RackRows(containerId, reset && i == 0, complete, part,
                    i == 0 ? gone : List.of()));
            i += PER_MESSAGE;
        } while (i < changed.size());
    }

    /**
     * Whether the card {@code stack} makes something whose name holds {@code query} (already in lower case), each of its
     * outputs named by {@code names}. The search box of the panel uses it.
     */
    public static boolean matches(ItemStack stack, String query, Function<ProcessingCard.Amount, Component> names) {
        Card card = Card.of(stack);
        if (card == null) return false;
        if (card instanceof ProcessingCard processing) {
            for (ProcessingCard.Amount output : processing.outputs()) {
                if (!output.isEmpty() && names.apply(output).getString().toLowerCase(Locale.ROOT).contains(query)) return true;
            }
            return false;
        }
        return card.result().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    /** Client side: what the server sent. */
    public void apply(RackPayloads.RackRows rows) {
        if (rows.reset()) racks = new LinkedHashMap<>();
        for (NetworkNode at : rows.gone()) racks.remove(at);
        for (RackView view : rows.racks()) racks.put(view.at(), view);
        complete = rows.complete();
        version++;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (controller == null || !(player instanceof ServerPlayer serverPlayer)) return;
        long time = serverPlayer.level().getGameTime();
        // Spread over open screens; at once after this player's own click, so the panel answers it straight away.
        if (lookNow || !synced || (time + containerId) % LOOK_EVERY == 0) {
            lookNow = false;
            if (stillValid(serverPlayer)) look(serverPlayer);
        }
        if (portList.due(serverPlayer, containerId) && stillValid(serverPlayer)) {
            portList.look(serverPlayer, containerId, PortList.usable(controller, serverPlayer));
        }
    }

    // --- the port list ---

    @Override
    public PortList portList() {
        return portList;
    }

    /**
     * Server side: the port the player asked to open from the list, or null with a line on the screen saying why not.
     * Refused unless this is the player's open, usable Rack Controller screen with power, the port is on its network and
     * loaded, the player may use the port, and it stands in the player's dimension.
     */
    public @Nullable AccessPortBlockEntity portToOpen(ServerPlayer player, NetworkNode at, int face) {
        if (controller == null || player.containerMenu != this || !stillValid(player)) return null;
        if (!controller.running()) {
            Notices.bad(player, Component.translatable("message.jasm.rack_controller.no_power"));
            return null;
        }
        return PortList.find(player, PortList.usable(controller, player), at, face);
    }

    @Override
    public boolean openPort(ServerPlayer player, NetworkNode at, int face) {
        AccessPortBlockEntity port = portToOpen(player, at, face);
        if (port == null || controller == null) return false;
        PortList.open(player, port, controller);
        return true;
    }

    /** Client side: the last message for this screen. */
    @Override
    public Notices.Shown notices() {
        return notices;
    }

    /**
     * Server side: a click on slot {@code slot} of the rack at {@code at}, which the player's panel showed holding
     * {@code expected}. A plain click takes the card into the hand, puts the held card in an empty slot, or swaps a
     * single held card with the one there. With {@code quick} (Shift) the card goes to the hotbar, then the inventory.
     * Refused when the slot no longer holds what the player saw (someone else got there first), so nothing happens
     * that they didn't mean. Returns whether anything moved.
     */
    public boolean click(Player player, NetworkNode at, int slot, ItemStack expected, boolean quick) {
        if (controller == null || player.containerMenu != this || !stillValid(player) || !controller.running()) return false;
        if (slot < 0 || slot >= RecipeRackBlockEntity.SLOTS) return false;
        RecipeRackBlockEntity rack = rackAt(player, at);
        if (rack == null) return false;
        lookNow = true;
        ItemStack there = rack.getItem(slot);
        if (!ItemStack.matches(there, expected)) return false;
        ItemStack held = getCarried();
        if (quick) {
            if (there.isEmpty()) return false;
            ItemStack moving = there.copy();
            int hotbar = 27;
            if (!moveItemStackTo(moving, hotbar, hotbar + 9, false) && !moveItemStackTo(moving, 0, hotbar, false)) return false;
            rack.setItem(slot, moving.isEmpty() ? ItemStack.EMPTY : moving);
            return true;
        }
        if (held.isEmpty()) {
            if (there.isEmpty()) return false;
            rack.setItem(slot, ItemStack.EMPTY);
            setCarried(there);
            return true;
        }
        if (!RecipeRackBlockEntity.accepts(held)) return false;
        if (there.isEmpty()) {
            ItemStack one = held.split(1);
            setCarried(held.isEmpty() ? ItemStack.EMPTY : held);
            rack.setItem(slot, one);
            return true;
        }
        if (held.getCount() != 1) return false;
        setCarried(there);
        rack.setItem(slot, held);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return controller == null || !controller.isRemoved() && stillValid(access, player, JasmBlocks.RACK_CONTROLLER.get())
                && MachineAccess.canUse(controller, player);
    }

    /**
     * Shift-click on a written card in the inventory: it goes into the first free slot of the first rack in the panel's
     * order (the search doesn't change which). Anything else, or a card no rack has room for, stays where it is. Only
     * the server moves it, straight into the real rack.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        ItemStack stack = clicked.getItem();
        if (controller == null || stack.isEmpty() || !RecipeRackBlockEntity.accepts(stack) || !controller.running() || !stillValid(player)) {
            return ItemStack.EMPTY;
        }
        List<RecipeRackBlockEntity> racks = new ArrayList<>(usableRacks(player));
        racks.sort(order(RecipeRackBlockEntity::label, RackControllerMenu::node));
        for (RecipeRackBlockEntity rack : racks) {
            for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) {
                if (!rack.getItem(i).isEmpty()) continue;
                ItemStack moved = stack.copyWithCount(1);
                clicked.remove(1);
                rack.setItem(i, moved);
                lookNow = true;
                return moved.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return controller == null ? null : controller.getBlockPos();
    }
}
