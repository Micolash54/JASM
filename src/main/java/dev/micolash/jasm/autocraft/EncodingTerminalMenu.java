package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.TrustList;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.List;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
import org.jspecify.annotations.Nullable;

/**
 * The Encoding Terminal's menu: card in and out, the pairing slot, the 3×3 ghost grid and what it makes, then the
 * player's inventory (27) and hotbar (9). Ghost slots copy what is clicked into them and never take the item.
 */
public class EncodingTerminalMenu extends AbstractContainerMenu {
    public static final int GRID_X = 30;
    public static final int GRID_Y = 18;
    public static final int PREVIEW_X = 124;
    public static final int PREVIEW_Y = 36;
    public static final int CARD_X = 8;
    public static final int CARD_IN_Y = 18;
    public static final int CARD_OUT_Y = 54;
    public static final int PAIR_X = 152;
    public static final int PAIR_Y = 18;
    public static final int INVENTORY_Y = 108;
    /** Where the terminal's messages show, under the grid. */
    public static final int MESSAGE_Y = 76;

    public static final int SLOT_CARD_IN = 0;
    public static final int SLOT_CARD_OUT = 1;
    public static final int SLOT_PAIR = 2;
    public static final int SLOT_GHOST = 3;
    public static final int SLOT_PREVIEW = SLOT_GHOST + 9;
    public static final int SLOT_INVENTORY = SLOT_PREVIEW + 1;

    public static final int BUTTON_ENCODE = 0;
    public static final int BUTTON_CLEAR = 1;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_STATE = 2;
    static final int DATA_RUNNING = 3;
    static final int DATA_PAIRED = 4;
    static final int DATA_COUNT = 5;

    /** Messages the screen can show, by number; 0 is none. */
    public static final List<String> MESSAGES = List.of("", "message.jasm.terminal.no_power", "message.jasm.terminal.no_card",
            "message.jasm.terminal.output_full", "message.jasm.terminal.empty_grid", "message.jasm.terminal.no_recipe",
            "message.jasm.terminal.encoded");

    private static final Identifier EMPTY_CARD = Jasm.id("container/empty_card");
    private static final Identifier EMPTY_DECK = Jasm.id("container/empty_deck");

    private final Container container;
    private final Container ghost;
    private final ContainerData data;
    /** The last message for this screen, and a count that goes up with each one so a repeat still shows. */
    private final ContainerData feedback = new SimpleContainerData(2);
    private final ContainerLevelAccess access;
    private final @Nullable EncodingTerminalBlockEntity terminal;
    private final Player player;
    /** Server side: whether the trust list was sent to this screen yet. */
    private boolean trustSent;
    /** Client side: what the server said about trust. */
    private boolean owner;
    private TrustList trust = TrustList.EMPTY;

    /** Server side. */
    public EncodingTerminalMenu(int containerId, Inventory inventory, EncodingTerminalBlockEntity terminal, ContainerLevelAccess access) {
        this(containerId, inventory, terminal, terminal.ghost(), terminal.preview(), terminal.data(), access, terminal);
    }

    /** Client side. */
    public EncodingTerminalMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(EncodingTerminalBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return EncodingTerminalBlockEntity.accepts(slot, stack);
            }
        }, new SimpleContainer(9), new SimpleContainer(1), new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    private EncodingTerminalMenu(int containerId, Inventory inventory, Container container, Container ghost, Container preview,
            ContainerData data, ContainerLevelAccess access, @Nullable EncodingTerminalBlockEntity terminal) {
        super(JasmMenus.ENCODING_TERMINAL.get(), containerId);
        this.container = container;
        this.ghost = ghost;
        this.data = data;
        this.access = access;
        this.terminal = terminal;
        this.player = inventory.player;
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.CARD_IN, CARD_X, CARD_IN_Y, EMPTY_CARD));
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.CARD_OUT, CARD_X, CARD_OUT_Y, null));
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.PAIR, PAIR_X, PAIR_Y, EMPTY_DECK));
        for (int i = 0; i < 9; i++) {
            addSlot(new FakeSlot(ghost, i, GRID_X + (i % 3) * 18, GRID_Y + (i / 3) * 18));
        }
        addSlot(new FakeSlot(preview, 0, PREVIEW_X, PREVIEW_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
        addDataSlots(feedback);
    }

    /** The last message's key, or empty. */
    public String message() {
        int index = feedback.get(0);
        return index > 0 && index < MESSAGES.size() ? MESSAGES.get(index) : "";
    }

    /** Goes up with every message. */
    public int messageCount() {
        return feedback.get(1);
    }

    private void tell(String key) {
        feedback.set(0, Math.max(0, MESSAGES.indexOf(key)));
        feedback.set(1, (feedback.get(1) + 1) & 0x7FFF);
    }

    public @Nullable EncodingTerminalBlockEntity terminal() {
        return terminal;
    }

    /** The trust list goes to the screen with the first update after opening. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!trustSent && terminal != null && player instanceof ServerPlayer serverPlayer) {
            trustSent = true;
            TerminalAccess.send(serverPlayer, this, terminal);
        }
    }

    public void setTrustView(boolean owner, TrustList trust) {
        this.owner = owner;
        this.trust = trust;
    }

    /** Client side: whether the viewer owns this terminal. */
    public boolean owner() {
        return owner;
    }

    /** Client side: the trusted players, as last sent. */
    public TrustList trust() {
        return trust;
    }

    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    public int capacity() {
        return EncodingTerminalBlockEntity.CAPACITY;
    }

    /** {@link EncodingTerminalBlockEntity#STATE_NONE}, {@code STATE_SHAPED} or {@code STATE_SHAPELESS}. */
    public int recipeState() {
        return data.get(DATA_STATE);
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** Whether the Deck in the pairing slot is paired with this terminal. */
    public boolean paired() {
        return data.get(DATA_PAIRED) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        if (terminal == null) {
            return true;
        }
        return !terminal.isRemoved() && stillValid(access, player, JasmBlocks.ENCODING_TERMINAL.get()) && terminal.isAuthorized(player);
    }

    /** Ghost slots: holding an item copies it in, empty-handed (or right-click) clears the slot. The preview can't be taken. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (slotIndex >= SLOT_GHOST && slotIndex < SLOT_PREVIEW) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
                ItemStack carried = getCarried();
                boolean clear = carried.isEmpty() || buttonNum == 1 || input == ContainerInput.QUICK_MOVE;
                ghost.setItem(slotIndex - SLOT_GHOST, clear ? ItemStack.EMPTY : carried.copyWithCount(1));
            }
            return;
        }
        if (slotIndex == SLOT_PREVIEW) {
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /** Puts {@code stack} (or nothing) in ghost slot {@code slot}: for JEI's recipe button and drag-and-drop. */
    public void setGhost(int slot, ItemStack stack) {
        if (slot >= 0 && slot < 9) {
            ghost.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (terminal == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        switch (id) {
            case BUTTON_ENCODE -> {
                String problem = terminal.encode((ServerLevel) serverPlayer.level());
                tell(problem == null ? "message.jasm.terminal.encoded" : problem);
                return true;
            }
            case BUTTON_CLEAR -> {
                for (int i = 0; i < 9; i++) {
                    ghost.setItem(i, ItemStack.EMPTY);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Shift-click: cards go to the input slot and Crafting Decks to the pairing slot; out of the terminal, items go
     * to the hotbar first, then the inventory.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem() || clicked instanceof FakeSlot) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        int hotbar = SLOT_INVENTORY + 27;
        boolean moved;
        if (index < SLOT_GHOST) {
            moved = moveItemStackTo(stack, hotbar, hotbar + 9, false) || moveItemStackTo(stack, SLOT_INVENTORY, hotbar, false);
        } else if (EncodingTerminalBlockEntity.accepts(EncodingTerminalBlockEntity.CARD_IN, stack)) {
            moved = moveItemStackTo(stack, SLOT_CARD_IN, SLOT_CARD_IN + 1, false);
        } else if (DeckItem.isCrafting(stack)) {
            moved = moveItemStackTo(stack, SLOT_PAIR, SLOT_PAIR + 1, false);
        } else {
            moved = false;
        }
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

    /** A slot that only takes what the terminal accepts there, with a faint picture while empty. */
    private static final class MachineSlot extends Slot {
        private final @Nullable Identifier icon;

        MachineSlot(Container container, int index, int x, int y, @Nullable Identifier icon) {
            super(container, index, x, y);
            this.icon = icon;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return EncodingTerminalBlockEntity.accepts(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return getContainerSlot() == EncodingTerminalBlockEntity.PAIR ? 1 : super.getMaxStackSize();
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return icon;
        }
    }

    /** A ghost or preview slot: shows an item, never takes or gives one. */
    public static final class FakeSlot extends Slot {
        FakeSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
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
        public boolean isFake() {
            return true;
        }
    }
}
