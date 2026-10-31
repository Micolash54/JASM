package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.LinkWindowCover;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.transfer.RedstoneUpgradeItem;
import dev.micolash.jasm.transfer.SpeedUpgradeItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.Networks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The port's upgrades, item buffer, Deck link, status and player inventory, the kinds of machine it can serve, and the
 * processing recipes: those it serves, and those in the Recipe Racks on its network the player may use. Only the
 * player with the screen open is sent the recipes.
 */
public class AccessPortMenu extends AbstractContainerMenu implements MachineView {
    public static final int MAX_NAME = 32;
    /** The main panel: as wide as the inventory's own panel. */
    public static final int WIDTH = 176;
    public static final int HEIGHT = 222;
    /** Left edge of the side keys, just past the main panel. */
    public static final int KEY_X = WIDTH;
    public static final int INVENTORY_Y = 138;
    /** Side keys in the right-hand column: the Deck Link, blocking mode and rename. */
    public static final int SIDE_KEYS = 3;
    public static final int BUFFER_X = (WIDTH - 8 * 18) / 2;
    public static final int BUFFER_Y = 106;
    /** Most recipes the list shows; a network with more lists the first ones its racks hold. */
    public static final int MAX_LISTED = 1_024;
    /** Ticks between two looks at the racks while the screen is open. */
    private static final int RACKS_EVERY = 20;
    public static final int SLOT_POWER = 0;
    public static final int SLOT_BUFFER = 1;
    public static final int SLOT_DECK_IN = SLOT_BUFFER + AccessPortBlockEntity.BUFFER_SLOTS;
    public static final int SLOT_DECK_OUT = SLOT_DECK_IN + 1;
    public static final int SLOT_SPEED = SLOT_DECK_OUT + 1;
    public static final int RESET_DECK = 0;
    public static final int TOGGLE_BLOCKING = 1;
    public static final int CYCLE_REDSTONE = 2;

    static final int DATA_FLOW_LOW = 0;
    static final int DATA_FLOW_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_LOCKED = 3;
    static final int DATA_DEFAULT_DECK = 4;
    static final int DATA_LINKED = 5;
    static final int DATA_BLOCKING = 6;
    static final int DATA_PAUSED = 7;
    static final int DATA_REDSTONE = 8;
    public static final int DATA_COUNT = 9;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable AccessPortBlockEntity port;
    /** Client side: the name when the screen opened. */
    private final String label;
    private @Nullable BlockPos openedAt;
    private List<MachineView> machines;
    private final Player player;
    private final int inventoryStart;
    private final int hotbarStart;
    /** Client side: where the Deck Link window lies over the screen. */
    private final LinkWindowCover linkCover = new LinkWindowCover();
    private String linkedPlayer = "";
    /** Server side: the name last sent, so it goes again only when it changes. */
    private final String[] lastPlayer = {null};
    /** The recipes the player may pick from; on the server, what was last sent. */
    private List<ProcessingCard> listed = List.of();
    /** The recipes the port serves; on the server, what was last sent. */
    private List<ActiveRecipe> active = List.of();
    private @Nullable Pasted pasted;
    private int pastes;
    /** Server side: every card in the network's racks at the last look, whoever may use them. */
    private Set<Card> onNetwork = Set.of();
    /** Server side: whether the recipes went out once yet, and how many times the list did. */
    private boolean synced;
    private int listSends;

    /** Server side. */
    public AccessPortMenu(int containerId, Inventory inventory, AccessPortBlockEntity port, ContainerLevelAccess access) {
        this(containerId, inventory, port.data(), access, port, port.label(), connectedMachines(port));
    }

    /** Client side. */
    public static AccessPortMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String label = buf.readUtf(MAX_NAME);
        List<MachineView> machines = MachineView.STREAM_CODEC.apply(ByteBufCodecs.list(6)).decode(buf);
        AccessPortMenu menu = new AccessPortMenu(containerId, inventory, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL,
                null, label, machines);
        menu.openedAt = pos;
        return menu;
    }

    /** Client side: where the port stands, as the server said when the screen opened. */
    public @Nullable BlockPos openedAt() {
        return openedAt;
    }

    private AccessPortMenu(int containerId, Inventory inventory, ContainerData data, ContainerLevelAccess access,
            @Nullable AccessPortBlockEntity port,
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
        addSlot(new Slot(container, AccessPortBlockEntity.POWER_SLOT, KEY_X + 2, PortUpgradeLayout.powerY(SIDE_KEYS)) {
            @Override
            public boolean isActive() { return !linkCover.covers(this); }
            @Override
            public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.POWER_UPGRADE.get()); }
            @Override
            public int getMaxStackSize() { return 1; }
            @Override
            public Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
        });
        for (int col = 0; col < AccessPortBlockEntity.BUFFER_SLOTS; col++) {
            addSlot(new Slot(container, col, BUFFER_X + col * 18, BUFFER_Y) {
                @Override
                public boolean isActive() { return !linkCover.covers(this); }
            });
        }
        addSlot(new Slot(container, AccessPortBlockEntity.DECK_IN, 0, 0) {
            @Override
            public boolean isActive() { return port != null || linkCover.open(); }
            @Override
            public boolean mayPlace(ItemStack stack) { return DeckItem.isDeck(stack); }
            @Override
            public int getMaxStackSize() { return 1; }
            @Override
            public Identifier getNoItemIcon() { return Jasm.id("container/empty_deck"); }
        });
        addSlot(new Slot(container, AccessPortBlockEntity.DECK_OUT, 0, 0) {
            @Override
            public boolean isActive() { return port != null || linkCover.open(); }
            @Override
            public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            addSlot(new Slot(container, AccessPortBlockEntity.SPEED_START + i, KEY_X + 2, PortUpgradeLayout.speedY(SIDE_KEYS, i)) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    int from = AccessPortBlockEntity.SPEED_START;
                    int to = AccessPortBlockEntity.INVENTORY_SIZE;
                    if (stack.is(JasmItems.REDSTONE_UPGRADE.get())) return RedstoneUpgradeItem.fits(container, from, to, getContainerSlot(), stack);
                    return stack.is(JasmItems.SPEED_UPGRADE.get()) && SpeedUpgradeItem.fits(container, from, to, getContainerSlot(), stack);
                }
                @Override
                public int getMaxStackSize() { return 1; }
                @Override
                public Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
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
        if (id == CYCLE_REDSTONE && port.hasRedstoneUpgrade()) {
            port.setRedstoneMode(port.redstoneMode().next());
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

    /** One kind of machine touching the port, and whether the port serves it. */
    public record MachineView(Component name, ItemStack icon, boolean chosen) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MachineView> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, MachineView::name,
                ItemStack.OPTIONAL_STREAM_CODEC, MachineView::icon,
                ByteBufCodecs.BOOL, MachineView::chosen,
                MachineView::new);
    }

    /** The kinds of machine touching the port now, in the order of its sides, with the one it serves marked. */
    static List<MachineView> connectedMachines(AccessPortBlockEntity port) {
        if (port.getLevel() == null) return List.of();
        Block served = port.servedType();
        return port.machineTypesInRange().stream()
                .map(type -> new MachineView(type.getName(), new ItemStack(type), type == served)).toList();
    }

    /**
     * Server side: the player picks which kind of machine the port serves. Refused if the menu is not valid for the player
     * or no machine of that kind touches the port.
     */
    public boolean chooseMachine(Player player, Identifier block) {
        if (port == null || !stillValid(player)) return false;
        Block type = BuiltInRegistries.BLOCK.getOptional(block).orElse(null);
        return type != null && port.chooseMachineType(type);
    }

    public List<MachineView> machines() { return machines; }

    // --- recipes ---

    /** A recipe the port serves, and whether its card is missing from every rack on the network. */
    public record ActiveRecipe(ProcessingCard recipe, boolean missing) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ActiveRecipe> STREAM_CODEC = StreamCodec.composite(
                ProcessingCard.STREAM_CODEC, ActiveRecipe::recipe,
                ByteBufCodecs.BOOL, ActiveRecipe::missing,
                ActiveRecipe::new);
    }

    /** The processing recipes the player may pick from, as the server last sent them. */
    public List<ProcessingCard> listed() { return listed; }

    public void setListed(List<ProcessingCard> recipes) { listed = List.copyOf(recipes); }

    /** The recipes the port serves, as the server last sent them. */
    public List<ActiveRecipe> active() { return active; }

    public void setActive(List<ActiveRecipe> recipes) { active = List.copyOf(recipes); }

    /** Server side: how many times the recipe list went to the player. */
    public int listSends() { return listSends; }

    private @Nullable CableNetwork network() {
        return port != null && port.getLevel() instanceof ServerLevel level ? Networks.at(level, port.getBlockPos()) : null;
    }

    /**
     * Looks at every rack on the port's network once: returns the processing recipes in the racks {@code player} may
     * use (each once, at most {@link #MAX_LISTED}), and fills {@code all} with every card in any rack.
     */
    private List<ProcessingCard> usable(Player player, Set<Card> all) {
        CableNetwork network = network();
        if (network == null) return List.of();
        Set<ProcessingCard> found = new LinkedHashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            boolean mayUse = MachineAccess.canUse(rack, player);
            for (Card card : rack.cards()) {
                all.add(card);
                if (mayUse && card instanceof ProcessingCard processing && found.size() < MAX_LISTED) found.add(processing.clean());
            }
        }
        return List.copyOf(found);
    }

    /** Whether {@code card} is in a powered rack on the port's network that {@code player} may use. */
    private boolean inUsableRack(Player player, ProcessingCard card) {
        CableNetwork network = network();
        if (network == null) return false;
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            if (MachineAccess.canUse(rack, player) && rack.cards().contains(card)) return true;
        }
        return false;
    }

    /**
     * Server side: the port starts serving {@code card}. Refused unless this is the player's open, usable port screen
     * and the recipe is in a rack on the network they may use.
     */
    public boolean addRecipe(Player player, ProcessingCard card) {
        if (port == null || player.containerMenu != this || !stillValid(player) || !inUsableRack(player, card)) return false;
        boolean added = port.select(card);
        if (added && player instanceof ServerPlayer serverPlayer) sendActive(serverPlayer);
        return added;
    }

    /** Server side: the port stops serving {@code card}. Any recipe may be taken off, whatever rack it came from. */
    public boolean removeRecipe(Player player, ProcessingCard card) {
        if (port == null || player.containerMenu != this || !stillValid(player)) return false;
        boolean removed = port.unselect(card.clean());
        if (removed && player instanceof ServerPlayer serverPlayer) sendActive(serverPlayer);
        return removed;
    }

    /**
     * A name the rename panel takes: at most {@link #MAX_NAME} letters once trimmed, none of them a control or
     * formatting character. Empty clears the name.
     */
    public static boolean validName(String name) {
        String cleaned = name.strip();
        if (cleaned.length() > MAX_NAME) return false;
        for (int i = 0; i < cleaned.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(cleaned.charAt(i))) return false;
        }
        return true;
    }

    /** Server side: names the port, if this is the player's open, usable screen and the name is valid. */
    public boolean rename(Player player, String name) {
        if (port == null || player.containerMenu != this || !stillValid(player) || !validName(name)) return false;
        port.setLabel(name);
        return true;
    }

    /**
     * What a paste did: the name the port has now, the recipes it took in, those that did not fit (past 16), those it
     * already served (add only), and how many of the taken ones no rack on the network holds.
     */
    public record Pasted(String name, boolean add, int added, int notFit, int already, int missing) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Pasted> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), Pasted::name,
                ByteBufCodecs.BOOL, Pasted::add,
                ByteBufCodecs.VAR_INT, Pasted::added,
                ByteBufCodecs.VAR_INT, Pasted::notFit,
                ByteBufCodecs.VAR_INT, Pasted::already,
                ByteBufCodecs.VAR_INT, Pasted::missing,
                Pasted::new);
    }

    /** Client side: what the last paste did, and how many pastes the server has answered. */
    public @Nullable Pasted pasted() { return pasted; }

    public int pastes() { return pastes; }

    public void setPasted(Pasted result) {
        pasted = result;
        pastes++;
    }

    /**
     * Server side: pastes settings copied from a port. {@code add} false replaces the name and the recipes; true adds the
     * recipes the port doesn't serve yet (until it serves 16) and keeps the name. Refused (null) unless this is the
     * player's open, usable port screen, the name is one the rename panel takes, there are at most 16 recipes and each
     * one makes something and takes something in. A recipe no rack holds is taken all the same, and shows as missing.
     */
    public @Nullable Pasted paste(Player player, String name, List<ProcessingCard> recipes, boolean add) {
        if (port == null || player.containerMenu != this || !stillValid(player) || !validName(name)
                || recipes.size() > AccessPortBlockEntity.MAX_RECIPES || !recipes.stream().allMatch(ProcessingCard::wellFormed)) {
            return null;
        }
        Set<ProcessingCard> wanted = new LinkedHashSet<>();
        for (ProcessingCard card : recipes) wanted.add(card.clean());
        List<ProcessingCard> taken = new ArrayList<>();
        int notFit = 0;
        int already = 0;
        if (add) {
            for (ProcessingCard card : wanted) {
                if (port.serves(card)) already++;
                else if (port.select(card)) taken.add(card);
                else notFit++;
            }
        } else {
            port.setLabel(name);
            port.replaceRecipes(List.copyOf(wanted));
            taken.addAll(wanted);
        }
        int missing = 0;
        if (player instanceof ServerPlayer serverPlayer) {
            // the marks must be those of the racks as they are now, not as they were a second ago
            syncRecipes(serverPlayer);
            for (ProcessingCard card : taken) if (!onNetwork.contains(card)) missing++;
        }
        Pasted result = new Pasted(port.label(), add, taken.size(), notFit, already, missing);
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection.hasChannel(CraftPayloads.PortPasted.TYPE)) {
            PacketDistributor.sendToPlayer(serverPlayer, new CraftPayloads.PortPasted(containerId, result));
        }
        return result;
    }

    /** Looks at the racks again and sends the list if it changed, then the served recipes with their missing marks. */
    private void syncRecipes(ServerPlayer player) {
        Set<Card> all = new HashSet<>();
        List<ProcessingCard> now = usable(player, all);
        onNetwork = all;
        if (!synced || !now.equals(listed)) {
            listed = now;
            listSends++;
            if (player.connection.hasChannel(CraftPayloads.PortRecipes.TYPE)) {
                PacketDistributor.sendToPlayer(player, new CraftPayloads.PortRecipes(containerId, now));
            }
        }
        synced = true;
        sendActive(player);
    }

    /** Sends the recipes the port serves if they, or which of them are missing, changed since last time. */
    private void sendActive(ServerPlayer player) {
        if (port == null) return;
        List<ActiveRecipe> now = new ArrayList<>();
        for (ProcessingCard card : port.recipes()) now.add(new ActiveRecipe(card, !onNetwork.contains(card)));
        if (now.equals(active)) return;
        active = List.copyOf(now);
        if (player.connection.hasChannel(CraftPayloads.PortActive.TYPE)) {
            PacketDistributor.sendToPlayer(player, new CraftPayloads.PortActive(containerId, active));
        }
    }

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
        if (port == null || !(player instanceof ServerPlayer serverPlayer)) return;
        long time = serverPlayer.level().getGameTime();
        // The racks once a second, spread out over open screens. The served recipes twice a second, for changes made
        // elsewhere (another player's screen); this player's own clicks are answered at once. Nothing about recipes
        // goes to a player who may no longer use the port: the game shuts their screen.
        boolean racksDue = !synced || (time + containerId) % RACKS_EVERY == 0;
        if ((racksDue || time % 10 == 0) && stillValid(serverPlayer)) {
            if (racksDue) {
                syncRecipes(serverPlayer);
            } else {
                sendActive(serverPlayer);
            }
        }
        if (time % 10 != 0) return;
        sendLinkedPlayer(serverPlayer, containerId, port, lastPlayer);
        List<MachineView> now = connectedMachines(port);
        boolean same = now.size() == machines.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i).name().equals(machines.get(i).name()) && ItemStack.matches(now.get(i).icon(), machines.get(i).icon())
                    && now.get(i).chosen() == machines.get(i).chosen();
        }
        if (!same) {
            machines = now;
            if (serverPlayer.connection.hasChannel(CraftPayloads.PortMachines.TYPE)) {
                PacketDistributor.sendToPlayer(serverPlayer, new CraftPayloads.PortMachines(containerId, now));
            }
        }
    }

    /** FE a tick the Power Upgrade passes on to the port's machines, averaged over the last second. */
    public int powerFlow() {
        return ContainerWords.join(data.get(DATA_FLOW_HIGH), data.get(DATA_FLOW_LOW));
    }

    /** Whether the port holds a Power Upgrade. */
    public boolean hasPowerUpgrade() {
        return getSlot(SLOT_POWER).getItem().is(JasmItems.POWER_UPGRADE.get());
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** Stopped by its Redstone Upgrade's mode. */
    public boolean paused() {
        return data.get(DATA_PAUSED) != 0;
    }

    public RedstoneMode redstoneMode() {
        return RedstoneMode.byId(data.get(DATA_REDSTONE));
    }

    public boolean hasRedstoneUpgrade() {
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) if (getSlot(SLOT_SPEED + i).getItem().is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        return false;
    }

    /** Whether a job has sets in the machine right now. */
    public boolean locked() {
        return data.get(DATA_LOCKED) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        return port == null
                || port.installed() && player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) <= 64 && MachineAccess.canUse(port, player);
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
        } else if (stack.is(JasmItems.REDSTONE_UPGRADE.get())) {
            moved = moveItemStackTo(stack, SLOT_SPEED, inventoryStart, false);
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
