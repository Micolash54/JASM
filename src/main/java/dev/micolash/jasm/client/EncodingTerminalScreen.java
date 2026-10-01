package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.registry.JasmBlocks;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Encoding Terminal screen: card in and out, the ghost grid and what it makes, with the charge along the top.
 * Side keys on the right open the Deck Link window for pairing Decks and, for the owner, the access list. The panel on
 * the left lists the machines a card can be written for: the Crafting Server first (ordinary crafting cards), then
 * every Access Port. With a port chosen, the grid takes amounts and what the machine gives back goes in a column of
 * three beside it.
 */
public class EncodingTerminalScreen extends JasmScreen<EncodingTerminalMenu> {
    private static final JasmButton.Icon LINK = new JasmButton.Icon(Jasm.id("icon/deck_link"), 12, 12);
    private JasmButton linkKey;
    private @Nullable DeckLinkWindow linkWindow;
    private static final int WIDTH = 176;
    private static final int HEIGHT = EncodingTerminalMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 60;
    private static final int ENCODE_WIDTH = 52;
    private static final JasmButton.Icon ACCESS = new JasmButton.Icon(Jasm.id("icon/access"), 7, 7);
    private TrustWindow trustWindow;
    private JasmButton accessButton;
    private JasmButton encodeButton;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final Identifier ARROW_DOWN = Jasm.id("icon/craft_arrow");
    private static final Identifier ARROW_RIGHT = Jasm.id("icon/craft_arrow_right");
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final int MESSAGE_TICKS = 80;
    /** The machine panel, left of the terminal: a list with a scroll bar beside it. */
    private static final int PANEL_W = 140;
    private static final int PANEL_X = -PANEL_W - 3;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 18;
    private static final int LIST_W = PANEL_W - 22;
    private static final int SCROLL_X = PANEL_W - 9;
    private static final int ROW_H = 18;
    private static final int ROWS = (HEIGHT - LIST_Y - 8) / ROW_H;
    private static final int HANDLE_HEIGHT = 15;
    /** Side keys on the right: the Deck Link, then the access list. */
    private static final int KEY_X = WIDTH;
    private boolean draggingHandle;
    private JasmFrame frame;
    /** Whether the Deck tab shows in place of the inventory; kept while the game runs. */
    private static boolean showDeck;
    /** The sheet under the inventory or the Deck list, and the tabs along its top. */
    private static final int SHEET_X = 4;
    private static final int SHEET_Y = EncodingTerminalMenu.INVENTORY_Y - 5;
    private static final int SHEET_WIDTH = WIDTH - 8;
    private static final int TAB_TOP = SHEET_Y - 11;
    /** The panel sprite's colours, for tabs drawn to match it. */
    private static final int OUTLINE = 0xFF11111B;
    private static final int BODY = 0xFF313244;
    private static final int LIGHT = 0xFF585B70;
    private static final int SHADOW = 0xFF181825;
    private static final int WELL = 0xFF1E1E2E;
    private EditBox deckSearch;
    private TerminalDeckList deckList;
    /** An example picked from the Deck list, following the mouse; placed into ghost slots by clicking or dragging. */
    private ItemStack ghostHeld = ItemStack.EMPTY;
    /** Ghost slots filled during the current press, so a drag fills each once. */
    private final Set<Integer> placed = new HashSet<>();
    private int seenCount;
    private int messageTicks;
    private int scroll;

    public EncodingTerminalScreen(EncodingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = EncodingTerminalMenu.INVENTORY_Y - 10;
        this.seenCount = menu.messageCount();
    }

    @Override
    protected void init() {
        super.init();
        frame = JasmFrame.rounded(new int[] {0, 0, imageWidth, imageHeight}, new int[] {PANEL_X, 0, PANEL_W + 6, imageHeight},
                JasmGui.sideStrip(KEY_X, 2));
        Component linkLabel = Component.translatable("screen.jasm.deck_link");
        linkKey = addRenderableWidget(JasmButton.icon(() -> LINK, linkLabel, b -> toggleLink(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        linkKey.setTooltip(Tooltip.create(linkLabel));
        if (linkWindow == null) {
            linkWindow = new DeckLinkWindow(font, menu.getSlot(EncodingTerminalMenu.SLOT_PAIR_IN),
                    menu.getSlot(EncodingTerminalMenu.SLOT_PAIR_OUT), menu.linkCover(), null, () -> {});
        }
        linkKey.setLatched(linkWindow.isOpen());
        encodeButton = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.terminal.encode"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_ENCODE),
                leftPos + EncodingTerminalMenu.PREVIEW_X + 8 - ENCODE_WIDTH / 2, topPos + 74, ENCODE_WIDTH, 14));
        JasmButton clear = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.terminal.clear"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_CLEAR),
                leftPos + EncodingTerminalMenu.GRID_X + 3 * 18 + 2, topPos + EncodingTerminalMenu.GRID_Y - 1, 11, 11);
        clear.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.clear")));
        addRenderableWidget(clear);
        trustWindow = new TrustWindow(menu, font);
        accessButton = JasmButton.icon(() -> ACCESS, Component.translatable("screen.jasm.terminal.access"),
                b -> trustWindow.toggle(leftPos + 8, topPos + 14), leftPos + KEY_X, topPos + JasmGui.sideKeyY(1),
                JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT);
        accessButton.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.access_hint")));
        addRenderableWidget(accessButton);
        deckSearch = new JasmField(font, leftPos + WIDTH - 8 - 58, topPos + TAB_TOP - 1, 58, 11, Component.translatable("screen.jasm.deck.search"));
        deckSearch.setHint(Component.translatable("screen.jasm.deck.search").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        deckSearch.setMaxLength(64);
        addRenderableWidget(deckSearch);
        deckList = new TerminalDeckList(menu, font, deckSearch, leftPos + 8, topPos + EncodingTerminalMenu.INVENTORY_Y);
        showDeckTab(showDeck);
    }

    private void toggleLink() {
        linkWindow.toggle(leftPos + KEY_X, topPos);
        linkKey.setLatched(linkWindow.isOpen());
    }

    /** What the Deck Link window says: whether the Deck in it is paired, or how to pair one. */
    private Component linkStatus() {
        if (menu.getSlot(EncodingTerminalMenu.SLOT_PAIR_OUT).hasItem()) {
            return menu.paired() ? Component.translatable("screen.jasm.terminal.linked").withColor(JasmGui.GOOD & 0xFFFFFF)
                    : Component.translatable("screen.jasm.terminal.relink");
        }
        if (menu.getSlot(EncodingTerminalMenu.SLOT_PAIR_IN).hasItem()) {
            return Component.translatable("screen.jasm.terminal.pairing");
        }
        return Component.translatable("screen.jasm.terminal.pair_hint");
    }

    // --- the inventory and Deck tabs ---

    private void showDeckTab(boolean deck) {
        showDeck = deck;
        menu.setDeckTab(deck);
        deckSearch.visible = deck;
        if (!deck) {
            deckSearch.setFocused(false);
        }
    }

    /** Where tab {@code i} (0 Inventory, 1 Deck) starts across the screen, and how wide it is. */
    private int tabX(int i) {
        return i == 0 ? SHEET_X + 3 : tabX(0) + tabWidth(0) + 2;
    }

    private int tabWidth(int i) {
        return font.width(tabLabel(i)) + 10;
    }

    private Component tabLabel(int i) {
        return i == 0 ? playerInventoryTitle : Component.translatable("screen.jasm.terminal.tab_deck");
    }

    /** The tab under the mouse: 0 Inventory, 1 Deck, or -1. */
    private int tabAt(double mouseX, double mouseY) {
        double rx = mouseX - leftPos;
        double ry = mouseY - topPos;
        if (ry < TAB_TOP || ry >= SHEET_Y) {
            return -1;
        }
        for (int i = 0; i < 2; i++) {
            if (rx >= tabX(i) && rx < tabX(i) + tabWidth(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The sheet the inventory or the Deck list sits on, and the two tabs over its top edge: the chosen one stands
     * out and runs into the sheet like a folder tab, the other sits back, lower and darker.
     */
    private void drawSheet(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int chosen = showDeck ? 1 : 0;
        int hovered = tabAt(mouseX, mouseY);
        int left = leftPos;
        int top = topPos;
        // The tab at the back first, so the sheet's edge covers its foot.
        int back = 1 - chosen;
        int bx = left + tabX(back);
        int by = top + TAB_TOP + 2;
        int bw = tabWidth(back);
        graphics.fill(bx, by, bx + bw, top + SHEET_Y + 1, OUTLINE);
        graphics.fill(bx + 1, by + 1, bx + bw - 1, top + SHEET_Y + 1, back == hovered ? JasmGui.SELECTED : WELL);
        JasmGui.panel(graphics, left + SHEET_X, top + SHEET_Y, SHEET_WIDTH, imageHeight - SHEET_Y - 4);
        // The chosen tab: outline, light top and left, shade on the right, and no line where it meets the sheet.
        int cx = left + tabX(chosen);
        int cy = top + TAB_TOP;
        int cw = tabWidth(chosen);
        int foot = top + SHEET_Y + 3;
        graphics.fill(cx, cy, cx + cw, foot, OUTLINE);
        graphics.fill(cx + 1, cy + 1, cx + cw - 1, foot, BODY);
        graphics.fill(cx + 1, cy + 1, cx + cw - 1, cy + 2, LIGHT);
        graphics.fill(cx + 1, cy + 1, cx + 2, foot, LIGHT);
        graphics.fill(cx + cw - 2, cy + 2, cx + cw - 1, foot, SHADOW);
    }

    /** The tab names, over the shapes {@link #drawSheet} drew. */
    private void drawTabLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int chosen = showDeck ? 1 : 0;
        int hovered = tabAt(mouseX, mouseY);
        for (int i = 0; i < 2; i++) {
            int y = i == chosen ? TAB_TOP + 3 : TAB_TOP + 4;
            int color = i == chosen ? JasmGui.TEXT : i == hovered ? JasmGui.SUBTEXT : JasmGui.MUTED;
            graphics.text(font, tabLabel(i), tabX(i) + 5, y, color, false);
        }
    }

    // --- picking examples from the Deck ---

    /** The ghost slot (0-8 the grid, 9-11 the outputs) under the mouse that takes items right now, or -1. */
    private int ghostSlotAt(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            int index = processingSlot(slot);
            if (index >= 0 && index < ghostSlots() && slot.isActive() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Does to ghost slot {@code slot} what a click with a real stack does: left puts the copy in (with its count in
     * processing mode), right puts in just one, or one more of the same item in processing mode. Once per slot for
     * each press, so a drag goes over each slot once.
     */
    private void placeGhost(int slot, boolean right) {
        if (placed.add(slot)) {
            ItemStack example = ghostHeld.copy();
            if (right) {
                ItemStack there = ghostItem(slot);
                boolean more = menu.processing() && ItemStack.isSameItemSameComponents(there, ghostHeld);
                example = ghostHeld.copyWithCount(more ? Math.min(menu.amount(slot) + 1, ghostHeld.getMaxStackSize()) : 1);
            }
            ClientPacketDistributor.sendToServer(new CraftPayloads.Ghost(menu.containerId, slot, List.of(example)));
        }
    }

    /** What ghost slot {@code index} (0-8 the grid, 9-11 the outputs) shows now. */
    private ItemStack ghostItem(int index) {
        for (Slot slot : menu.slots) {
            if (processingSlot(slot) == index) {
                return slot.getItem();
            }
        }
        return ItemStack.EMPTY;
    }

    // --- the machine panel ---

    /** The panels that extend beyond the main screen. */
    public List<Rect2i> sidePanelAreas() {
        var areas = new ArrayList<Rect2i>();
        areas.add(panelArea());
        int[] strip = JasmGui.sideStrip(KEY_X, 2);
        areas.add(new Rect2i(leftPos + strip[0], topPos + strip[1], strip[2], strip[3]));
        if (linkWindow != null) linkWindow.area().ifPresent(areas::add);
        if (trustWindow != null) trustWindow.area().ifPresent(areas::add);
        return areas;
    }

    /** Where the machine panel sits on screen. */
    public Rect2i panelArea() {
        return new Rect2i(leftPos + PANEL_X, topPos, PANEL_W + 3, imageHeight);
    }

    private boolean inPanel(double x, double y) {
        return x >= leftPos + PANEL_X && x < leftPos && y >= topPos && y < topPos + imageHeight;
    }

    private int maxScroll() {
        return Math.max(0, rows() - ROWS);
    }

    private int handleOffset() {
        int max = maxScroll();
        return max == 0 ? 0 : Math.round((ROWS * ROW_H - HANDLE_HEIGHT) * (scroll / (float) max));
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        double rx = mouseX - leftPos - PANEL_X - SCROLL_X;
        double ry = mouseY - topPos - LIST_Y;
        return rx >= -1 && rx < 9 && ry >= 0 && ry < ROWS * ROW_H;
    }

    private void scrollToMouse(double mouseY) {
        double offset = mouseY - topPos - LIST_Y - HANDLE_HEIGHT / 2.0;
        scroll = (int) Math.round(Math.clamp(offset / (ROWS * ROW_H - HANDLE_HEIGHT), 0.0, 1.0) * maxScroll());
    }

    /** Rows of the list: the Crafting Server, then the machines. */
    private int rows() {
        return 1 + menu.machines().size();
    }

    /** The list row under the mouse, or -1. */
    private int rowAt(double x, double y) {
        int left = leftPos + PANEL_X + LIST_X;
        int top = topPos + LIST_Y;
        if (x < left || x >= left + LIST_W || y < top || y >= top + ROWS * ROW_H) {
            return -1;
        }
        int row = scroll + (int) ((y - top) / ROW_H);
        return row < rows() ? row : -1;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + PANEL_X;
        int y = topPos;
        graphics.text(font, Component.translatable("screen.jasm.terminal.machines"), x + LIST_X, y + 6, JasmGui.TEXT, false);
        JasmGui.inset(graphics, x + LIST_X - 1, y + LIST_Y - 1, LIST_W + 2, ROWS * ROW_H + 2);
        scroll = Math.clamp(scroll, 0, maxScroll());
        JasmGui.scrollBar(graphics, x + SCROLL_X, y + LIST_Y - 1, 8, ROWS * ROW_H + 2, handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < ROWS && scroll + i < rows(); i++) {
            int row = scroll + i;
            int ry = y + LIST_Y + i * ROW_H;
            boolean chosen;
            String name;
            int color;
            ItemStack icon;
            if (row == 0) {
                chosen = !menu.processing();
                name = Component.translatable("screen.jasm.terminal.crafting_server").getString();
                color = JasmGui.TEXT;
                icon = new ItemStack(JasmBlocks.CRAFTING_SERVER.get());
            } else {
                EncodingTerminalMenu.MachineView view = menu.machines().get(row - 1);
                chosen = view.selected();
                name = view.present() ? view.name() : Component.translatable("screen.jasm.terminal.machine_missing").getString();
                color = view.present() ? JasmGui.TEXT : JasmGui.BAD;
                icon = view.icon();
            }
            if (chosen) {
                graphics.fill(x + LIST_X, ry, x + LIST_X + LIST_W, ry + ROW_H, JasmGui.SELECTED);
            }
            if (row == hovered) {
                graphics.fill(x + LIST_X, ry, x + LIST_X + LIST_W, ry + ROW_H, JasmGui.HOVER);
            }
            // A tick box: filled when chosen.
            int bx = x + LIST_X + 3;
            int by = ry + 6;
            graphics.fill(bx, by, bx + 6, by + 6, JasmGui.MUTED);
            graphics.fill(bx + 1, by + 1, bx + 5, by + 5, chosen ? JasmGui.GOOD : JasmGui.SHADE);
            // The machine's block, then its name.
            if (!icon.isEmpty()) {
                graphics.item(icon, bx + 9, ry + 1);
            }
            int textX = bx + 27;
            int room = x + LIST_X + LIST_W - 3 - textX;
            String shown = font.width(name) > room ? font.plainSubstrByWidth(name, room - font.width("...")) + "..." : name;
            graphics.text(font, shown, textX, ry + 5, color, false);
        }
    }

    private void clickRow(int row) {
        if (row == 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_CRAFTING_SERVER);
        } else if (row > 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_MACHINE + row - 1);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (linkWindow.contains(event.x(), event.y())) {
            return linkWindow.mouseClicked(event, doubleClick) || super.mouseClicked(event, doubleClick);
        }
        if (trustWindow.contains(event.x(), event.y())) {
            return trustWindow.mouseClicked(event, doubleClick);
        }
        if (onScrollBar(event.x(), event.y()) && maxScroll() > 0) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        if (inPanel(event.x(), event.y())) {
            int row = rowAt(event.x(), event.y());
            if (row >= 0) {
                clickRow(row);
            }
            return true;
        }
        boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        int tab = tabAt(event.x(), event.y());
        if (tab >= 0) {
            if (left) {
                showDeckTab(tab == 1);
            }
            return true;
        }
        placed.clear();
        if (showDeck && deckList.mouseClicked(event.x(), event.y())) {
            return true;
        }
        if (showDeck && deckList.inGrid(event.x(), event.y())) {
            clickDeckList(event, left, right);
            return true;
        }
        if (!ghostHeld.isEmpty() && (left || right)) {
            int slot = ghostSlotAt(event.x(), event.y());
            if (slot >= 0) {
                placeGhost(slot, right);
                return true;
            }
            // The copy only goes into the grid: clicking anywhere else lets go of it.
            if (!deckSearch.isMouseOver(event.x(), event.y())) {
                ghostHeld = ItemStack.EMPTY;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * The Deck list clicks like a chest, except that what comes out is a copy and nothing leaves the Deck. Left takes
     * a stack, right half of one, Shift does nothing. Holding a copy, right lets go of it and left swaps it for what is
     * under the mouse (or just lets go over an empty cell). In the grid, left puts the copy in and right puts in one.
     */
    private void clickDeckList(net.minecraft.client.input.MouseButtonEvent event, boolean left, boolean right) {
        if (event.hasShiftDown() || !(left || right) || !menu.getCarried().isEmpty()) {
            return;
        }
        if (right && !ghostHeld.isEmpty()) {
            ghostHeld = ItemStack.EMPTY;
            return;
        }
        ghostHeld = ItemStack.EMPTY;
        var entry = deckList.entryAt(event.x(), event.y());
        if (entry != null) {
            int stack = (int) Math.min(entry.count(), entry.key().getMaxStackSize());
            ghostHeld = entry.key().toStack(right ? (stack + 1) / 2 : stack);
        }
    }

    /** Holding a copy, dragging over ghost slots does to each what a click there would. On the Deck list's scroll bar it scrolls. */
    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (linkWindow.mouseDragged(event, width, height) || trustWindow.mouseDragged(event, width, height)) {
            return true;
        }
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        if (showDeck && deckList.mouseDragged(event.y())) {
            return true;
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (!ghostHeld.isEmpty() && (right || event.button() == InputConstants.MOUSE_BUTTON_LEFT)) {
            int slot = ghostSlotAt(event.x(), event.y());
            if (slot >= 0) {
                placeGhost(slot, right);
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (linkWindow.mouseReleased() | trustWindow.mouseReleased()) {
            return true;
        }
        if (draggingHandle) {
            draggingHandle = false;
            return true;
        }
        if (deckList.mouseReleased()) {
            return true;
        }
        if (!ghostHeld.isEmpty()) {
            placed.clear();
            return true;
        }
        return super.mouseReleased(event);
    }

    /** The side panels sit outside the terminal, but clicking them doesn't throw the held item away. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        boolean inside = sidePanelAreas().stream().anyMatch(area -> area.contains((int) mouseX, (int) mouseY));
        return !inside && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /** Processing slot (0-8 the grid, 9-11 the outputs) of a slot of the menu, or -1. */
    private int processingSlot(Slot slot) {
        if (slot.index >= EncodingTerminalMenu.SLOT_GHOST && slot.index < EncodingTerminalMenu.SLOT_PREVIEW) {
            return slot.index - EncodingTerminalMenu.SLOT_GHOST;
        }
        if (slot.index >= EncodingTerminalMenu.SLOT_OUTPUTS && slot.index < EncodingTerminalMenu.SLOT_INVENTORY) {
            return 9 + slot.index - EncodingTerminalMenu.SLOT_OUTPUTS;
        }
        return -1;
    }

    /** Over the machine list it scrolls; over a filled processing slot it changes the amount (by 10 with Shift). */
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (linkWindow.contains(x, y)) {
            return true;
        }
        if (trustWindow.contains(x, y)) {
            return trustWindow.mouseScrolled(scrollY);
        }
        if (inPanel(x, y)) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        if (showDeck && deckList.contains(x, y)) {
            deckList.scroll(scrollY);
            return true;
        }
        if (menu.processing() && hoveredSlot != null && hoveredSlot.hasItem() && scrollY != 0) {
            int slot = processingSlot(hoveredSlot);
            if (slot >= 0) {
                boolean shift = minecraft.hasShiftDown();
                int op = scrollY > 0 ? (shift ? 2 : 0) : (shift ? 3 : 1);
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_AMOUNT + slot * 4 + op);
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (linkWindow.isOpen() && event.isEscape()) {
            linkWindow.close();
            linkKey.setLatched(false);
            return true;
        }
        if (trustWindow.isOpen() && trustWindow.keyPressed(event)) {
            return true;
        }
        if (event.isEscape() && !ghostHeld.isEmpty()) {
            ghostHeld = ItemStack.EMPTY;
            return true;
        }
        // Typing in the Deck search doesn't close the screen or use hotbar keys.
        if (deckSearch.isFocused() && !event.isEscape()) {
            deckSearch.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (trustWindow.isOpen() && trustWindow.charTyped(event)) {
            return true;
        }
        if (deckSearch.isFocused()) {
            return deckSearch.charTyped(event);
        }
        return super.charTyped(event);
    }

    /** Ghost slot {@code i} on screen (0-8 the grid, 9-11 the outputs), for dropping items from JEI. */
    public Rect2i ghostSlotArea(int i) {
        if (i >= 9) {
            return new Rect2i(leftPos + EncodingTerminalMenu.OUTPUTS_X, topPos + EncodingTerminalMenu.OUTPUTS_Y + (i - 9) * 18, 16, 16);
        }
        return new Rect2i(leftPos + EncodingTerminalMenu.GRID_X + (i % 3) * 18, topPos + EncodingTerminalMenu.GRID_Y + (i / 3) * 18, 16, 16);
    }

    /** How many ghost slots take items right now: the grid, and in processing mode the outputs too. */
    public int ghostSlots() {
        return menu.processing() ? EncodingTerminalBlockEntity.AMOUNTS : 9;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        frame.draw(graphics, x, y);
        drawSheet(graphics, mouseX, mouseY);
        // The Deck Link window draws its own two slots.
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.index != EncodingTerminalMenu.SLOT_PAIR_IN && slot.index != EncodingTerminalMenu.SLOT_PAIR_OUT) {
                JasmGui.slot(graphics, x + slot.x, y + slot.y);
            }
        }
        // Card in, down to card out; grid across to what it makes.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_DOWN, x + EncodingTerminalMenu.CARD_X + 3, y + 40, 9, 9);
        int arrowX = EncodingTerminalMenu.OUTPUTS_X - 16;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_RIGHT, x + arrowX, y + EncodingTerminalMenu.PREVIEW_Y + 3, 9, 9);
        JasmGui.bar(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        if (showDeck) {
            deckList.drawBackground(graphics);
        }
        drawPanel(graphics, mouseX, mouseY);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        accessButton.active = menu.owner();
        accessButton.setLatched(trustWindow.isOpen());
        linkWindow.sync(leftPos, topPos);
        // Under the windows nothing lights up or shows a tooltip, except the Deck Link window's own slots.
        boolean over = trustWindow.contains(realMouseX, realMouseY) || linkWindow.hidesMouse(realMouseX, realMouseY);
        int mouseX = over ? -1000 : realMouseX;
        int mouseY = over ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (showDeck) {
            deckList.drawItems(graphics, mouseX, mouseY);
            if (ghostHeld.isEmpty() && menu.getCarried().isEmpty()) {
                var key = deckList.keyAt(mouseX, mouseY);
                if (key != null) {
                    ItemStack stack = key.toStack(1);
                    graphics.setTooltipForNextFrame(font, deckList.tooltip(mouseX, mouseY, getTooltipFromContainerItem(stack)),
                            stack.getTooltipImage(), mouseX, mouseY);
                }
            }
        }
        if (menu.processing()) {
            drawAmounts(graphics);
        }
        if (trustWindow.isOpen()) {
            graphics.nextStratum();
            trustWindow.draw(graphics, realMouseX, realMouseY, a);
        }
        if (linkWindow.isOpen()) {
            graphics.nextStratum();
            linkWindow.draw(graphics, realMouseX, realMouseY, a, linkStatus());
        }
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
        Slot pair = menu.getSlot(EncodingTerminalMenu.SLOT_PAIR_IN);
        if (hoveredSlot == pair && !pair.hasItem() && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, List.of(Component.translatable("screen.jasm.terminal.pair_hint").getVisualOrderText()),
                    mouseX, mouseY);
        }
        if (menu.processing() && hoveredSlot != null && !hoveredSlot.hasItem() && processingSlot(hoveredSlot) >= 9 && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.terminal.output_hint"), 160), mouseX, mouseY);
        }
        int row = rowAt(mouseX, mouseY);
        if (row > 0) {
            EncodingTerminalMenu.MachineView view = menu.machines().get(row - 1);
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.literal(view.present() ? view.name() : Component.translatable("screen.jasm.terminal.machine_missing").getString())
                            .getVisualOrderText(),
                    Component.translatable("screen.jasm.terminal.machine_at", view.at().port().getX(), view.at().port().getY(), view.at().port().getZ())
                            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText()), realMouseX, realMouseY);
        } else if (row == 0) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.terminal.crafting_server_hint"), 160),
                    realMouseX, realMouseY);
        }
        if (tabAt(realMouseX, realMouseY) == 1) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.terminal.tab_deck_hint"), 170),
                    realMouseX, realMouseY);
        }
        // The picked example follows the mouse, over everything else.
        if (!ghostHeld.isEmpty()) {
            graphics.nextStratum();
            graphics.item(ghostHeld, realMouseX - 8, realMouseY - 8);
            graphics.itemDecorations(font, ghostHeld, realMouseX - 8, realMouseY - 8, ghostHeld.getCount() > 1 ? String.valueOf(ghostHeld.getCount()) : "");
        }
    }

    /** The amount written on each filled processing slot, over its item. */
    private void drawAmounts(GuiGraphicsExtractor graphics) {
        graphics.nextStratum();
        for (int slot = 0; slot < EncodingTerminalBlockEntity.AMOUNTS; slot++) {
            int amount = menu.amount(slot);
            if (amount <= 0) {
                continue;
            }
            Rect2i area = ghostSlotArea(slot);
            String text = String.valueOf(amount);
            graphics.text(font, text, area.getX() + 17 - font.width(text), area.getY() + 9, 0xFFFFFFFF, true);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.messageCount() != seenCount) {
            seenCount = menu.messageCount();
            messageTicks = MESSAGE_TICKS;
        } else if (messageTicks > 0) {
            messageTicks--;
        }
    }

    /**
     * The line under the grid: the last message for a few seconds, otherwise a lack of power, how pairing the Deck is
     * going, or in processing mode how the amounts work.
     */
    private void drawMessage(GuiGraphicsExtractor graphics) {
        Component text = null;
        int color = JasmGui.BAD;
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            text = notice;
            color = menu.notices().ok() ? JasmGui.GOOD : JasmGui.BAD;
        } else if (messageTicks > 0 && !menu.message().isEmpty()) {
            text = Component.translatable(menu.message());
            color = menu.message().equals("message.jasm.terminal.encoded") ? JasmGui.GOOD : JasmGui.BAD;
        } else if (!menu.running()) {
            text = Component.translatable("message.jasm.terminal.no_power");
        } else if (menu.processing()) {
            text = Component.translatable("screen.jasm.terminal.processing_hint");
            color = JasmGui.MUTED;
        }
        if (text != null) {
            List<FormattedCharSequence> lines = font.split(text, imageWidth - 16);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.text(font, lines.get(i), 8, EncodingTerminalMenu.MESSAGE_Y + i * 9, color, false);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        int room = BAR_X - 6 - titleLabelX;
        String name = title.getString();
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        graphics.text(font, name, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        drawTabLabels(graphics, xm, ym);
        drawMessage(graphics);
    }
}
