package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.delivery.DeliveryState;
import dev.micolash.jasm.delivery.DeliveryView;
import dev.micolash.jasm.delivery.InboxContainer;
import dev.micolash.jasm.delivery.SendContainer;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import dev.micolash.jasm.wafer.WaferItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's menu: its wafer slots, then the player's inventory (27 slots) and hotbar (9), then on a Crafting Deck
 * the 3×3 grid and its result. The Deck itself stays locked in its slot while open, and the menu closes if that
 * exact Deck leaves the slot.
 *
 * <p>The wafer slots sit in a side panel on the left, up to {@link #SIDE_ROWS} per column; the main panel starts at
 * {@link #mainX()} with the item grid at the top and the inventory at the bottom. A Crafting Deck has a box between
 * them with the player's armour and off-hand, the 3×3 grid and its result; those slots come after the upgrade.
 *
 * <p>Last come the Deck to Deck window's slots: the send grid and the inbox. The screen moves them with the window;
 * while it is open, the Deck's own slots under it stop taking clicks.
 */
public class DeckMenu extends AbstractContainerMenu implements Notices.Board {
    private static final Identifier EMPTY_WAFER = Jasm.id("container/empty_wafer");
    /** Width of the main panel. */
    public static final int MAIN_WIDTH = 204;
    /** Most wafer slots in one column of the side panel. */
    public static final int SIDE_ROWS = 8;
    /** Top edge of the wafer panel, just under the search row. */
    public static final int SIDE_TOP = 19;
    /** Item grid rows at the smallest size, which is what the server lays out; the client may show more. */
    public static final int MIN_ROWS = 6;
    /** Top of the first item row in the grid. */
    public static final int GRID_Y = 38;
    /** The crafting box under the grid, with its border and the gap below it. */
    public static final int CRAFT_BOX = 78;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Identifier[] ARMOR_ICONS = {Jasm.id("container/empty_helmet"), Jasm.id("container/empty_chestplate"),
            Jasm.id("container/empty_leggings"), Jasm.id("container/empty_boots")};

    private final Notices.Shown notices = new Notices.Shown();
    private final Player player;
    private final int deckSlot;
    private final ItemStack deck;
    private final DeckWaferContainer wafers;
    private final int waferSlots;
    private int rows = MIN_ROWS;
    private final int sideColumns;
    private final int sideRows;
    private final @Nullable DeckGridContainer grid;
    private final ResultContainer result = new ResultContainer();
    /** First grid slot and the result slot in {@link #slots}; -1 on a normal Deck. */
    private final int gridStart;
    private final int resultSlot;
    private final DeckUpgradeContainer upgrade;
    private final int upgradeSlot;
    /** The four armour slots, head first, then the off-hand; -1 on a normal Deck. */
    private final int armorStart;
    /** While the grid is being filled in one go, the result is worked out once at the end. */
    private boolean placing;
    /** Client side only: what the server has told this screen. */
    private final DeckView view = new DeckView();
    /** The wafer being emptied, how much there was to move and how much has moved so far. */
    private int emptyingSlot = -1;
    private long emptyingTotal;
    private long emptyingMoved;
    private final SendContainer send;
    private final Container inbox;
    private final int sendStart;
    private final int inboxStart;
    /** Client side only: the Deck to Deck window's trips and people. */
    private final DeliveryView deliveries = new DeliveryView();
    /** Client side only: the Deck to Deck window's area {x, y, width, height} from the screen's corner, or null while shut. */
    private int @Nullable [] window;

    /** Server side. */
    public DeckMenu(int containerId, Inventory inventory, int deckSlot) {
        super(JasmMenus.DECK.get(), containerId);
        this.player = inventory.player;
        this.deckSlot = deckSlot;
        this.deck = inventory.getItem(deckSlot);
        this.wafers = new DeckWaferContainer(deck, player);
        this.waferSlots = wafers.getContainerSize();
        this.sideColumns = Math.max(1, (waferSlots + SIDE_ROWS - 1) / SIDE_ROWS);
        this.sideRows = Math.max(1, (waferSlots + sideColumns - 1) / sideColumns);

        // Wafers fill the side panel column by column, top to bottom.
        for (int i = 0; i < waferSlots; i++) {
            addSlot(new WaferSlot(wafers, i, 5 + (i / sideRows) * 18, SIDE_TOP + 7 + (i % sideRows) * 18));
        }
        int x = mainX();
        for (int i = 0; i < 36; i++) {
            int index = i < 27 ? 9 + i : i - 27;
            addSlot(playerSlot(inventory, index, x + 21 + index % 9 * 18, 0));
        }

        if (DeckItem.isCrafting(deck)) {
            grid = new DeckGridContainer(deck, this, !player.level().isClientSide());
            gridStart = slots.size();
            for (int i = 0; i < DeckGridContainer.SIZE; i++) {
                addSlot(new GridSlot(grid, i, x + 99 + i % 3 * 18, 0));
            }
            resultSlot = slots.size();
            addSlot(new GridResultSlot(player, grid, result, x + 172, 0));
            if (player instanceof ServerPlayer serverPlayer) {
                result.setItem(0, craft(serverPlayer, null));
            }
        } else {
            grid = null;
            gridStart = -1;
            resultSlot = -1;
        }
        upgrade = new DeckUpgradeContainer(deck, this, !player.level().isClientSide());
        upgradeSlot = slots.size();
        addSlot(new CoveredSlot(upgrade, 0, mainX() - 21, SIDE_TOP + 15 + sideRows * 18) {
            @Override
            public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.DIMENSION_UPGRADE.get()); }
            @Override
            public int getMaxStackSize() { return 1; }
            @Override
            public Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
        });
        if (grid != null) {
            armorStart = slots.size();
            for (int i = 0; i < ARMOR.length; i++) {
                int index = 39 - i;
                addSlot(index == deckSlot
                        ? new LockedSlot(inventory, index, x + 10, 0)
                        : new ArmorSlot(inventory, player, ARMOR[i], index, ARMOR_ICONS[i], x + 10));
            }
            addSlot(deckSlot == Inventory.SLOT_OFFHAND
                    ? new LockedSlot(inventory, Inventory.SLOT_OFFHAND, x + 70, 0)
                    : new OffhandSlot(inventory, player, x + 70));
        } else {
            armorStart = -1;
        }
        send = new SendContainer(deck, !player.level().isClientSide());
        sendStart = slots.size();
        for (int i = 0; i < SendContainer.SIZE; i++) {
            addSlot(new SendSlot(send, i, i < sendSlots()));
        }
        inbox = player instanceof ServerPlayer serverPlayer
                ? new InboxContainer(serverPlayer.level().getServer(), player.getUUID())
                : new SimpleContainer(DeliveryState.INBOX);
        inboxStart = slots.size();
        for (int i = 0; i < DeliveryState.INBOX; i++) {
            addSlot(new InboxSlot(inbox, i));
        }
        layout(MIN_ROWS);
    }

    /** Client side: the server tells which inventory slot holds the Deck. */
    public static DeckMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        return new DeckMenu(containerId, inventory, data.readVarInt());
    }

    private Slot playerSlot(Inventory inventory, int index, int x, int y) {
        return index == deckSlot ? new LockedSlot(inventory, index, x, y) : new CoveredSlot(inventory, index, x, y);
    }

    /** Client side: whether the Deck to Deck window lies over any part of {@code slot}. */
    private boolean covered(Slot slot) {
        int[] w = window;
        return w != null && slot.x + 16 > w[0] && slot.x < w[0] + w[2] && slot.y + 16 > w[1] && slot.y < w[1] + w[3];
    }

    /** Client side: where the Deck to Deck window is, or null when it shuts. */
    public void setWindow(int @Nullable [] window) {
        this.window = window;
    }

    /** The first send grid slot in {@link #slots}; nine follow, though only the Deck's tier's worth take items. */
    public int sendStart() {
        return sendStart;
    }

    /** The first inbox slot in {@link #slots}. */
    public int inboxStart() {
        return inboxStart;
    }

    /** Send grid slots this Deck's tier has. */
    public int sendSlots() {
        return deck.getItem() instanceof DeckItem item ? item.tier().sendSlots() : 1;
    }

    /** What is in the send grid's usable slots. */
    public List<ItemStack> sendItems() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < sendSlots(); i++) items.add(send.getItem(i));
        return items;
    }

    /** Empties the send grid's usable slots after a send. */
    public void clearSend() {
        for (int i = 0; i < sendSlots(); i++) send.setItem(i, ItemStack.EMPTY);
    }

    public Container inbox() {
        return inbox;
    }

    public DeliveryView deliveries() {
        return deliveries;
    }

    public ItemStack deck() {
        return deck;
    }

    public int deckSlot() {
        return deckSlot;
    }

    /** Item grid rows shown. */
    public int rows() {
        return rows;
    }

    /** First row under the item grid's last row of items. */
    public int gridEnd() {
        return GRID_Y + rows * 18 + 2;
    }

    /** Top of the crafting box, right under the item grid. */
    public int craftY() {
        return gridEnd();
    }

    /** The light bottom edge of the grid's well, which takes in the crafting box on a Crafting Deck. */
    public int wellBottom() {
        return isCrafting() ? gridEnd() + CRAFT_BOX : gridEnd();
    }

    /** Top of the player's inventory in this Deck's screen. */
    public int inventoryY() {
        return wellBottom() + 14;
    }

    /** Height of the whole screen. */
    public int screenHeight() {
        return inventoryY() + 82;
    }

    /**
     * Lays the slots out around an item grid {@code rows} tall. The server keeps the smallest size; a taller grid on the
     * client moves everything under it down, and only the screen cares where slots are drawn.
     */
    public void layout(int rows) {
        this.rows = rows;
        int y = inventoryY();
        for (int i = waferSlots; i < waferSlots + 36; i++) {
            Slot slot = slots.get(i);
            int index = slot.getContainerSlot();
            slot.y = index < 9 ? y + 58 : y + (index - 9) / 9 * 18;
        }
        if (grid != null) {
            for (int i = 0; i < DeckGridContainer.SIZE; i++) {
                slots.get(gridStart + i).y = craftY() + 12 + i / 3 * 18;
            }
            slots.get(resultSlot).y = craftY() + 30;
            for (int i = 0; i < ARMOR.length; i++) {
                slots.get(armorStart + i).y = craftY() + 3 + i * 18;
            }
            slots.get(armorStart + ARMOR.length).y = craftY() + 57;
        }
    }

    /** Wafer rows in the side panel. */
    public int sideRows() {
        return sideRows;
    }

    /** Where the main panel starts, right of the wafer panel. */
    public int mainX() {
        return 8 + sideColumns * 18;
    }

    /** Width of the whole screen: the main panel with the scroll bar column, and the tab column. */
    public int screenWidth() {
        return mainX() + 241;
    }

    public int waferSlots() {
        return waferSlots;
    }

    public int upgradeSlot() { return upgradeSlot; }
    /** The first armour slot's index in {@link #slots}, the off-hand four after it; -1 on a normal Deck. */
    public int armorStart() {
        return armorStart;
    }
    public boolean dimensionAllowed() { return DeckItem.worksIn(player.getInventory().getItem(deckSlot), player.level()); }

    public DeckWaferContainer wafers() {
        return wafers;
    }

    public DeckView view() {
        return view;
    }

    public boolean isCrafting() {
        return grid != null;
    }

    public @Nullable DeckGridContainer grid() {
        return grid;
    }

    /** The result slot's index in {@link #slots}, or -1 on a normal Deck. */
    public int resultSlot() {
        return resultSlot;
    }

    /** The first grid slot's index in {@link #slots}, or -1 on a normal Deck. */
    public int gridStart() {
        return gridStart;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.getInventory().getItem(deckSlot) == deck && deck.getItem() instanceof DeckItem;
    }

    /** The Deck's own slot can't be clicked, and number keys or the off-hand key can't swap it away. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!dimensionAllowed() && gridStart >= 0 && slotIndex >= gridStart && slotIndex <= resultSlot) return;
        if (slotIndex >= 0 && slotIndex < slots.size() && slots.get(slotIndex) instanceof LockedSlot) {
            return;
        }
        if (input == ContainerInput.SWAP && buttonNum == deckSlot) {
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /**
     * Shift-click: a wafer moves between the Deck's wafer slots and the inventory; any other item from the
     * inventory is stored on the Deck's wafers. On a Crafting Deck, a grid item goes back to the wafers (or the
     * inventory if they're full), and the result crafts once into the inventory; vanilla repeats that while the
     * refilled grid keeps making the same thing.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || slot instanceof LockedSlot) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (armorStart >= 0 && index >= armorStart && index <= armorStart + ARMOR.length) {
            moveItemStackTo(stack, waferSlots, waferSlots + 36, false);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (index >= inboxStart) {
            // Inbox: hotbar first, then the inventory, like a wafer.
            int hotbar = waferSlots + 27;
            if (!moveItemStackTo(stack, hotbar, hotbar + 9, false)) {
                moveItemStackTo(stack, waferSlots, hotbar, false);
            }
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (index >= sendStart) {
            // Send grid: back onto the wafers, or into the inventory if they won't take it.
            if (player instanceof ServerPlayer serverPlayer && dimensionAllowed()) store(serverPlayer, stack);
            moveItemStackTo(stack, waferSlots, waferSlots + 36, false);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (index == upgradeSlot) {
            moveItemStackTo(stack, waferSlots, waferSlots + 36, false);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (stack.is(JasmItems.DIMENSION_UPGRADE.get()) && index >= waferSlots && index < waferSlots + 36) {
            moveItemStackTo(stack, upgradeSlot, upgradeSlot + 1, false);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        if (!dimensionAllowed() && index >= waferSlots && !(index < waferSlots + 36 && stack.getItem() instanceof WaferItem)) return ItemStack.EMPTY;
        if (index == resultSlot) {
            return craftIntoInventory(player, slot);
        }
        if (index >= gridStart && gridStart >= 0) {
            if (player instanceof ServerPlayer serverPlayer) {
                store(serverPlayer, stack);
            }
            moveItemStackTo(stack, waferSlots, waferSlots + 36, false);
            slot.setChanged();
        } else if (index < waferSlots) {
            // Hotbar first, left to right, then the inventory from its top-left slot.
            int hotbar = waferSlots + 27;
            if (!moveItemStackTo(stack, hotbar, hotbar + 9, false)) {
                moveItemStackTo(stack, waferSlots, hotbar, false);
            }
            slot.setChanged();
        } else if (stack.getItem() instanceof WaferItem) {
            moveItemStackTo(stack, 0, waferSlots, false);
            slot.setChanged();
        } else if (player instanceof ServerPlayer serverPlayer) {
            DeckStorage.deposit(WaferStore.get(serverPlayer.level().getServer()), deck, stack, serverPlayer, DeckStorage.Excess.VOID);
            slot.setChanged();
        }
        return ItemStack.EMPTY;
    }

    /** One craft, all of it into the inventory, or nothing if it doesn't all fit. */
    private ItemStack craftIntoInventory(Player player, Slot slot) {
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (!fitsInInventory(player.getInventory(), stack)) {
            return ItemStack.EMPTY;
        }
        stack.getItem().onCraftedBy(stack, player);
        if (!moveItemStackTo(stack, waferSlots, waferSlots + 36, true)) {
            return ItemStack.EMPTY;
        }
        slot.onQuickCraft(stack, before);
        slot.setByPlayer(ItemStack.EMPTY);
        slot.onTake(player, stack);
        return before;
    }

    private static boolean fitsInInventory(Inventory inventory, ItemStack stack) {
        int room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack there = inventory.getItem(i);
            if (there.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(there, stack)) {
                room += Math.max(0, there.getMaxStackSize() - there.getCount());
            }
            if (room >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    /** Double-clicking to gather a stack never pulls from the result slot. */
    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        return target.container != result && super.canTakeItemForPickAll(carried, target);
    }

    /** The grid changed: work out what it makes now. */
    @Override
    public void slotsChanged(Container container) {
        if (container == upgrade && player instanceof ServerPlayer serverPlayer) {
            if (dimensionAllowed()) {
                DeckStorage.activate(WaferStore.get(serverPlayer.level().getServer()), deck, serverPlayer);
            }
            if (grid != null) updateResult(serverPlayer, null);
            DeckViewTracker.markDirty(this);
        }
        if (container == grid && !placing && player instanceof ServerPlayer serverPlayer) {
            updateResult(serverPlayer, null);
        }
    }

    private void updateResult(ServerPlayer player, @Nullable RecipeHolder<CraftingRecipe> hint) {
        ItemStack made = craft(player, hint);
        result.setItem(0, made);
        setRemoteSlot(resultSlot, made);
        player.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), resultSlot, made));
    }

    /** What the grid makes, as a crafting table would work it out. */
    private ItemStack craft(ServerPlayer player, @Nullable RecipeHolder<CraftingRecipe> hint) {
        if (grid == null || !dimensionAllowed()) {
            return ItemStack.EMPTY;
        }
        ServerLevel level = player.level();
        CraftingInput input = grid.asCraftInput();
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level, hint);
        if (recipe.isPresent() && result.setRecipeUsed(player, recipe.get())) {
            ItemStack made = recipe.get().value().assemble(input);
            if (made.isItemEnabled(level.enabledFeatures())) {
                return made;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Stores {@code stack} on the wafers, shrinking it by what fit. */
    private void store(ServerPlayer player, ItemStack stack) {
        DeckStorage.depositQuietly(WaferStore.get(player.level().getServer()), deck, stack, player, DeckStorage.Excess.VOID);
    }

    /** After a craft: every grid slot that ran out gets one more of what was there, if the wafers have it. */
    private void refill(ServerPlayer player, List<ItemStack> before) {
        if (grid == null) {
            return;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        placing = true;
        DeckStorage.Checked storage = null;
        for (int i = 0; i < before.size(); i++) {
            ItemStack was = before.get(i);
            if (was.isEmpty() || !grid.getItem(i).isEmpty()) {
                continue;
            }
            if (storage == null) storage = DeckStorage.checked(store, deck, player);
            List<ItemStack> taken = storage.withdrawQuietly(ItemResource.of(was), 1);
            if (!taken.isEmpty()) {
                grid.setItem(i, taken.getFirst());
            }
        }
        placing = false;
        updateResult(player, null);
    }

    /** Puts everything in the grid back on the wafers. What doesn't fit stays in the grid. */
    public void returnGrid(ServerPlayer player) {
        returnGrid(player, null);
    }

    private void returnGrid(ServerPlayer player, DeckStorage.@Nullable Checked storage) {
        if (grid == null || !dimensionAllowed()) {
            return;
        }
        placing = true;
        var incoming = new LinkedHashMap<ItemResource, Long>();
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty()) incoming.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
        }
        var accepted = DeckStorage.hasPower(deck)
                ? (storage == null
                        ? DeckStorage.depositAmounts(WaferStore.get(player.level().getServer()), deck, incoming, player, Long.MAX_VALUE,
                                DeckStorage.Excess.VOID)
                        : storage.depositAmounts(incoming, DeckStorage.Excess.VOID))
                : new LinkedHashMap<ItemResource, Long>();
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty()) {
                ItemResource key = ItemResource.of(stack);
                int take = (int) Math.min(stack.getCount(), accepted.getOrDefault(key, 0L));
                stack.shrink(take);
                accepted.put(key, accepted.getOrDefault(key, 0L) - take);
                grid.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        placing = false;
        updateResult(player, null);
    }

    /** Moves everything in the grid into the player's inventory. What doesn't fit stays in the grid. */
    public void gridToInventory(ServerPlayer player) {
        if (grid == null) {
            return;
        }
        placing = true;
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack stack = grid.getItem(i).copy();
            if (stack.isEmpty()) continue;
            // Through the inventory slots rather than Inventory.add, which in creative throws away what doesn't fit.
            moveItemStackTo(stack, waferSlots, waferSlots + 36, false);
            grid.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
        placing = false;
        updateResult(player, null);
    }

    /**
     * Fills the grid for a recipe (JEI's "+"): the grid is emptied onto the wafers first, then each slot takes one of
     * its allowed items (or as many sets as fit, with {@code max}), from the wafers first and then the inventory,
     * picking the item there is most of. Slots with nothing to take stay empty.
     */
    public void fillGrid(ServerPlayer player, List<List<ItemResource>> wanted, boolean max) {
        if (grid == null || !dimensionAllowed() || wanted.size() > DeckGridContainer.SIZE) {
            return;
        }
        var storage = DeckStorage.checked(WaferStore.get(player.level().getServer()), deck, player);
        returnGrid(player, storage);
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack left = grid.getItem(i);
            if (!left.isEmpty()) {
                // Something that couldn't go back to the wafers is in the way; move it to the inventory.
                grid.setItem(i, ItemStack.EMPTY);
                player.getInventory().placeItemBackInInventory(left);
            }
        }
        Map<ItemResource, Long> available = new HashMap<>();
        List<@Nullable ItemResource> chosen = new ArrayList<>();
        for (List<ItemResource> options : wanted) {
            ItemResource best = null;
            long bestCount = 0;
            for (ItemResource option : options) {
                if (option.isEmpty() || !allowedInGrid(option.toStack(1))) {
                    continue;
                }
                long count = available.computeIfAbsent(option, key -> storage.count(key) + inventoryCount(player.getInventory(), key));
                if (count > bestCount) {
                    best = option;
                    bestCount = count;
                }
            }
            chosen.add(best);
        }
        int sets = max ? Integer.MAX_VALUE : 1;
        Map<ItemResource, Integer> uses = new HashMap<>();
        chosen.forEach(key -> {
            if (key != null) {
                uses.merge(key, 1, Integer::sum);
            }
        });
        for (Map.Entry<ItemResource, Integer> use : uses.entrySet()) {
            sets = (int) Math.min(sets, Math.min(use.getKey().getMaxStackSize(), available.get(use.getKey()) / use.getValue()));
        }
        placing = true;
        for (int i = 0; i < chosen.size(); i++) {
            ItemResource key = chosen.get(i);
            if (key == null || sets <= 0) {
                continue;
            }
            int needed = sets;
            ItemStack placed = ItemStack.EMPTY;
            for (ItemStack taken : storage.withdrawQuietly(key, needed)) {
                placed = placed.isEmpty() ? taken : placed.copyWithCount(placed.getCount() + taken.getCount());
            }
            needed -= placed.getCount();
            Inventory inventory = player.getInventory();
            for (int s = 0; s < Inventory.INVENTORY_SIZE && needed > 0; s++) {
                ItemStack there = inventory.getItem(s);
                if (s != deckSlot && key.matches(there)) {
                    ItemStack part = there.split(Math.min(needed, there.getCount()));
                    placed = placed.isEmpty() ? part : placed.copyWithCount(placed.getCount() + part.getCount());
                    needed -= part.getCount();
                }
            }
            if (!placed.isEmpty()) {
                grid.setItem(i, placed);
            }
        }
        placing = false;
        updateResult(player, null);
    }

    private static long inventoryCount(Inventory inventory, ItemResource key) {
        long count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (key.matches(inventory.getItem(i))) {
                count += inventory.getItem(i).getCount();
            }
        }
        return count;
    }

    /** Wafers and Decks never go in the grid: their contents must always stay where the save rules can see them. */
    public static boolean allowedInGrid(ItemStack stack) {
        return !(stack.getItem() instanceof WaferItem) && !(stack.getItem() instanceof WaferHolderItem);
    }

    /** Vanilla slot code can change a grid stack without telling its container; catch up every tick. */
    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide()) {
            if (emptyingSlot >= 0 && player instanceof ServerPlayer serverPlayer) {
                DeckNetwork.stepEmptying(serverPlayer, this);
            }
            upgrade.flush();
            send.flush();
            if (grid != null) {
                grid.flush();
            }
        }
        super.broadcastChanges();
    }

    boolean emptying() { return emptyingSlot >= 0; }
    int emptyingSlot() { return emptyingSlot; }
    long emptyingTotal() { return emptyingTotal; }

    void startEmptying(int slot, long total) {
        emptyingSlot = slot;
        emptyingTotal = total;
        emptyingMoved = 0;
    }

    /** Adds to what has moved and returns the running total. */
    long addEmptied(long moved) {
        emptyingMoved += moved;
        return emptyingMoved;
    }

    void stopEmptying() { emptyingSlot = -1; }

    /** The grid stays as it is, saved on the Deck, until the player sends it somewhere. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        emptyingSlot = -1;
        if (!player.level().isClientSide()) {
            upgrade.flush();
            send.flush();
            if (grid != null) {
                grid.flush();
            }
        }
    }

    /** One of the Deck's own slots: on the client it stops taking clicks while the Deck to Deck window lies over it. */
    private class CoveredSlot extends Slot {
        CoveredSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() { return !covered(this); }
    }

    /** A send grid slot: anything but wafers and Decks, and only the tier's worth. On the client, only while the window is open. */
    private final class SendSlot extends Slot {
        private final boolean usable;

        SendSlot(SendContainer container, int index, boolean usable) {
            super(container, index, -1000, -1000);
            this.usable = usable;
        }

        @Override
        public boolean mayPlace(ItemStack stack) { return usable && allowedInGrid(stack); }

        @Override
        public boolean isActive() { return usable && (!player.level().isClientSide() || window != null); }
    }

    /** An inbox slot: take only. On the client, only while the window is open. */
    private final class InboxSlot extends Slot {
        InboxSlot(Container container, int index) {
            super(container, index, -1000, -1000);
        }

        @Override
        public boolean mayPlace(ItemStack stack) { return false; }

        @Override
        public boolean isActive() { return !player.level().isClientSide() || window != null; }
    }

    /** Accepts wafers only, one per slot. */
    private final class WaferSlot extends CoveredSlot {
        WaferSlot(DeckWaferContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        /** Shows a faint wafer while empty. */
        @Override
        public Identifier getNoItemIcon() {
            return EMPTY_WAFER;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof WaferItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** A crafting grid slot: anything but wafers and Decks. */
    private final class GridSlot extends CoveredSlot {
        GridSlot(DeckGridContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return dimensionAllowed() && allowedInGrid(stack);
        }

        @Override
        public boolean mayPickup(Player player) { return dimensionAllowed(); }
    }

    /** Taking the result refills the grid from the wafers. */
    private final class GridResultSlot extends ResultSlot {
        GridResultSlot(Player player, DeckGridContainer grid, ResultContainer result, int x, int y) {
            super(player, grid, result, 0, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return dimensionAllowed() && super.mayPickup(player);
        }

        @Override
        public boolean isActive() { return !covered(this); }

        @Override
        public void onTake(Player player, ItemStack carried) {
            List<ItemStack> before = new ArrayList<>();
            for (int i = 0; i < DeckGridContainer.SIZE; i++) {
                before.add(grid.getItem(i).copy());
            }
            super.onTake(player, carried);
            if (player instanceof ServerPlayer serverPlayer) {
                refill(serverPlayer, before);
            }
        }
    }

    /** One armour piece, as in the player's own inventory. */
    private final class ArmorSlot extends Slot {
        private final Player owner;
        private final EquipmentSlot equipment;
        private final Identifier icon;

        ArmorSlot(Inventory inventory, Player owner, EquipmentSlot equipment, int index, Identifier icon, int x) {
            super(inventory, index, x, 0);
            this.owner = owner;
            this.equipment = equipment;
            this.icon = icon;
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            owner.onEquipItem(equipment, previous, stack);
            super.setByPlayer(stack, previous);
        }

        @Override
        public int getMaxStackSize() { return 1; }
        @Override
        public boolean mayPlace(ItemStack stack) { return stack.canEquip(equipment, owner); }
        @Override
        public boolean isActive() { return owner.canUseSlot(equipment) && !covered(this); }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack stack = getItem();
            return (stack.isEmpty() || player.isCreative() || !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE))
                    && super.mayPickup(player);
        }

        @Override
        public Identifier getNoItemIcon() { return icon; }
    }

    /** The off-hand, as in the player's own inventory. */
    private final class OffhandSlot extends CoveredSlot {
        private final Player owner;

        OffhandSlot(Inventory inventory, Player owner, int x) {
            super(inventory, Inventory.SLOT_OFFHAND, x, 0);
            this.owner = owner;
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            owner.onEquipItem(EquipmentSlot.OFFHAND, previous, stack);
            super.setByPlayer(stack, previous);
        }

        @Override
        public Identifier getNoItemIcon() { return Jasm.id("container/empty_shield"); }
    }

    /** The slot holding the open Deck. */
    private final class LockedSlot extends CoveredSlot {
        LockedSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /** Client side: the last message for this screen. */
    @Override
    public Notices.Shown notices() {
        return notices;
    }
}
