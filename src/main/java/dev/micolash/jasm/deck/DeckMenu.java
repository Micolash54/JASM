package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import dev.micolash.jasm.wafer.WaferItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
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
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's menu: its wafer slots, then the player's inventory (27 slots) and hotbar (9), then on a Crafting Deck
 * the 3×3 grid and its result. The Deck itself stays locked in its slot while open, and the menu closes if that
 * exact Deck leaves the slot.
 *
 * <p>The wafer slots sit in a side panel on the left, up to {@link #SIDE_ROWS} per column; the main panel (grid and
 * inventory) starts at {@link #mainX()}; a Crafting Deck's grid sits in a panel on the right at {@link #craftX()}.
 */
public class DeckMenu extends AbstractContainerMenu {
    private static final Identifier EMPTY_WAFER = Jasm.id("container/empty_wafer");
    public static final int INVENTORY_Y = 150;
    /** Width of the main panel. */
    public static final int MAIN_WIDTH = 190;
    /** Most wafer slots in one column of the side panel. */
    public static final int SIDE_ROWS = 8;
    /** Padding inside the side panels, and the gap between them and the main panel. */
    public static final int SIDE_PAD = 7;
    public static final int SIDE_GAP = 2;
    /** The crafting panel: the grid at the top, the result below it. */
    public static final int CRAFT_WIDTH = SIDE_PAD * 2 + 3 * 18;
    public static final int CRAFT_RESULT_Y = SIDE_PAD + 1 + 3 * 18 + 14;
    public static final int CRAFT_HEIGHT = CRAFT_RESULT_Y + 16 + SIDE_PAD + 1;

    private final Player player;
    private final int deckSlot;
    private final ItemStack deck;
    private final DeckWaferContainer wafers;
    private final int waferSlots;
    private final int inventoryY;
    private final int sideColumns;
    private final int sideRows;
    private final @Nullable DeckGridContainer grid;
    private final ResultContainer result = new ResultContainer();
    /** First grid slot and the result slot in {@link #slots}; -1 on a normal Deck. */
    private final int gridStart;
    private final int resultSlot;
    /** While the grid is being filled in one go, the result is worked out once at the end. */
    private boolean placing;
    /** Client side only: what the server has told this screen. */
    private final DeckView view = new DeckView();

    /** Server side. */
    public DeckMenu(int containerId, Inventory inventory, int deckSlot) {
        super(JasmMenus.DECK.get(), containerId);
        this.player = inventory.player;
        this.deckSlot = deckSlot;
        this.deck = inventory.getItem(deckSlot);
        this.wafers = new DeckWaferContainer(deck, player);
        this.waferSlots = wafers.getContainerSize();
        this.inventoryY = INVENTORY_Y;
        this.sideColumns = Math.max(1, (waferSlots + SIDE_ROWS - 1) / SIDE_ROWS);
        this.sideRows = Math.max(1, (waferSlots + sideColumns - 1) / sideColumns);

        // Wafers fill the side panel column by column, top to bottom.
        for (int i = 0; i < waferSlots; i++) {
            addSlot(new WaferSlot(wafers, i, SIDE_PAD + 1 + (i / sideRows) * 18, SIDE_PAD + 1 + (i % sideRows) * 18));
        }
        int x = mainX();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int index = 9 + row * 9 + column;
                addSlot(playerSlot(inventory, index, x + 8 + column * 18, inventoryY + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(playerSlot(inventory, column, x + 8 + column * 18, inventoryY + 58));
        }

        if (DeckItem.isCrafting(deck)) {
            grid = new DeckGridContainer(deck, this, !player.level().isClientSide());
            gridStart = slots.size();
            int cx = craftX() + SIDE_PAD + 1;
            for (int i = 0; i < DeckGridContainer.SIZE; i++) {
                addSlot(new GridSlot(grid, i, cx + (i % 3) * 18, SIDE_PAD + 1 + (i / 3) * 18));
            }
            resultSlot = slots.size();
            addSlot(new GridResultSlot(player, grid, result, cx + 18, CRAFT_RESULT_Y));
            if (player instanceof ServerPlayer serverPlayer) {
                result.setItem(0, craft(serverPlayer, null));
            }
        } else {
            grid = null;
            gridStart = -1;
            resultSlot = -1;
        }
    }

    /** Client side: the server tells which inventory slot holds the Deck. */
    public static DeckMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        return new DeckMenu(containerId, inventory, data.readVarInt());
    }

    private Slot playerSlot(Inventory inventory, int index, int x, int y) {
        return index == deckSlot ? new LockedSlot(inventory, index, x, y) : new Slot(inventory, index, x, y);
    }

    public ItemStack deck() {
        return deck;
    }

    public int deckSlot() {
        return deckSlot;
    }

    /** Top of the player's inventory in this Deck's screen. */
    public int inventoryY() {
        return inventoryY;
    }

    /** Width of the wafer side panel. */
    public int sideWidth() {
        return SIDE_PAD * 2 + sideColumns * 18;
    }

    /** Height of the wafer side panel. */
    public int sideHeight() {
        return SIDE_PAD * 2 + sideRows * 18;
    }

    /** Where the main panel starts, right of the side panel. */
    public int mainX() {
        return sideWidth() + SIDE_GAP;
    }

    /** Where the crafting panel starts, right of the main panel. */
    public int craftX() {
        return mainX() + MAIN_WIDTH + SIDE_GAP;
    }

    /** Width of the whole screen. */
    public int screenWidth() {
        return isCrafting() ? craftX() + CRAFT_WIDTH : mainX() + MAIN_WIDTH;
    }

    public int waferSlots() {
        return waferSlots;
    }

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
            wafers.flush();
            DeckStorage.deposit(WaferStore.get(serverPlayer.level().getServer()), deck, stack, serverPlayer);
            wafers.reload();
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
        if (grid == null) {
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
        wafers.flush();
        DeckStorage.depositQuietly(WaferStore.get(player.level().getServer()), deck, stack, player);
        wafers.reload();
    }

    /** After a craft: every grid slot that ran out gets one more of what was there, if the wafers have it. */
    private void refill(ServerPlayer player, List<ItemStack> before) {
        if (grid == null) {
            return;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        wafers.flush();
        placing = true;
        for (int i = 0; i < before.size(); i++) {
            ItemStack was = before.get(i);
            if (was.isEmpty() || !grid.getItem(i).isEmpty()) {
                continue;
            }
            List<ItemStack> taken = DeckStorage.withdrawQuietly(store, deck, ItemResource.of(was), 1, player);
            if (!taken.isEmpty()) {
                grid.setItem(i, taken.getFirst());
            }
        }
        placing = false;
        wafers.reload();
        updateResult(player, null);
    }

    /** Puts everything in the grid back on the wafers. What doesn't fit stays in the grid. */
    public void returnGrid(ServerPlayer player) {
        if (grid == null) {
            return;
        }
        placing = true;
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty()) {
                store(player, stack);
                grid.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
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
        if (grid == null || wanted.size() > DeckGridContainer.SIZE) {
            return;
        }
        returnGrid(player);
        for (int i = 0; i < DeckGridContainer.SIZE; i++) {
            ItemStack left = grid.getItem(i);
            if (!left.isEmpty()) {
                // Something that couldn't go back to the wafers is in the way; move it to the inventory.
                grid.setItem(i, ItemStack.EMPTY);
                player.getInventory().placeItemBackInInventory(left, Prediction.SERVER_ONLY);
            }
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        wafers.flush();
        DeckStorage.checkAll(store, deck, player);
        Map<ItemResource, Long> available = new HashMap<>();
        List<@Nullable ItemResource> chosen = new ArrayList<>();
        for (List<ItemResource> options : wanted) {
            ItemResource best = null;
            long bestCount = 0;
            for (ItemResource option : options) {
                if (option.isEmpty() || !allowedInGrid(option.toStack(1))) {
                    continue;
                }
                long count = available.computeIfAbsent(option, key -> DeckStorage.count(store, deck, key) + inventoryCount(player.getInventory(), key));
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
            for (ItemStack taken : DeckStorage.withdrawQuietly(store, deck, key, needed, player)) {
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
        wafers.reload();
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

    /** Vanilla slot code can change a stack without telling its container; catch up every tick. */
    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide()) {
            wafers.flush();
            if (grid != null) {
                grid.flush();
            }
        }
        super.broadcastChanges();
    }

    /**
     * The grid goes back onto the wafers; what doesn't fit stays in it, saved on the Deck. Not when logging out: the
     * player's file is already written by then, and moving items after that could leave them in both places.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            if (player instanceof ServerPlayer serverPlayer && grid != null && stillValid(player) && !serverPlayer.hasDisconnected()) {
                returnGrid(serverPlayer);
            }
            wafers.flush();
            if (grid != null) {
                grid.flush();
            }
        }
    }

    /** Accepts wafers only, one per slot. */
    private static final class WaferSlot extends Slot {
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
    private static final class GridSlot extends Slot {
        GridSlot(DeckGridContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return allowedInGrid(stack);
        }
    }

    /** Taking the result refills the grid from the wafers. */
    private final class GridResultSlot extends ResultSlot {
        GridResultSlot(Player player, DeckGridContainer grid, ResultContainer result, int x, int y) {
            super(player, grid, result, 0, x, y);
        }

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

    /** The slot holding the open Deck. */
    private static final class LockedSlot extends Slot {
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
}
