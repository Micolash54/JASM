package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.SearchQuery;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.deck.DeckView;
import dev.micolash.jasm.storage.WaferSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck screen: wafer slots in a side panel on the left; in the main panel, charge and wafer status, a
 * searchable, scrollable grid of everything on the Deck's wafers, and the player's inventory. Right-clicking a wafer
 * opens its settings (priority and filter) in a small window on top, which can be dragged by its title bar.
 */
public class DeckScreen extends AbstractContainerScreen<DeckMenu> {
    /** Width of the main panel; the side panel adds to it on the left. */
    private static final int MAIN_WIDTH = 190;
    private static final int COLUMNS = 9;
    private static final int ROWS = 6;
    private static final int CHARGE_WIDTH = 60;
    private static final int SCROLL_WIDTH = 10;
    private static final int SCROLL_HEIGHT = ROWS * 18 - 2;
    private static final int HANDLE_HEIGHT = 15;

    private static final JasmButton.Icon SORT_NAME = new JasmButton.Icon(Jasm.id("icon/sort_name"), 7, 5);
    private static final JasmButton.Icon SORT_AMOUNT = new JasmButton.Icon(Jasm.id("icon/sort_amount"), 7, 5);
    private static final JasmButton.Icon ARROW_UP = new JasmButton.Icon(Jasm.id("icon/arrow_up"), 5, 3);
    private static final JasmButton.Icon ARROW_DOWN = new JasmButton.Icon(Jasm.id("icon/arrow_down"), 5, 3);
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private EditBox search;
    private GridEntries.Sort sort = GridEntries.Sort.NAME;
    private boolean ascending = true;
    private int scrollRow;
    private int builtVersion = -1;
    private String builtQuery = "";
    private List<GridEntries.Entry<ItemResource>> visible = List.of();
    private boolean draggingHandle;
    /** Left edge of the main panel, and the grid and scroll bar inside it. */
    private final int mainX;
    private final int gridX;
    private final int scrollX;
    /** Grid and status line sit just above the inventory. */
    private final int gridY;
    private final int statusY;
    /** The wafer slot whose settings are open, or -1; and the settings as edited here. */
    private int editing = -1;
    private WaferSettings draft = WaferSettings.DEFAULT;
    /** The settings window's buttons, each with its place in the window. Drawn and clicked by hand, above the rest. */
    private final List<Placed> settingsButtons = new ArrayList<>();
    private Button modeButton;
    /** Where the settings window sits, from the screen's corner. Kept while the Deck stays open. */
    private int windowDX;
    private int windowDY;
    /** While dragging the window: where in it the mouse took hold; -1 when not dragging. */
    private int grabX = -1;
    private int grabY;

    private record Placed(Button button, int x, int y) {}

    public DeckScreen(DeckMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.mainX() + MAIN_WIDTH, menu.inventoryY() + 58 + 18 + 6);
        this.mainX = menu.mainX();
        this.gridX = mainX + 8;
        this.scrollX = gridX + COLUMNS * 18 + 3;
        this.titleLabelX = mainX + 8;
        this.inventoryLabelX = mainX + 8;
        this.inventoryLabelY = menu.inventoryY() - 10;
        this.gridY = menu.inventoryY() - 12 - ROWS * 18;
        this.statusY = gridY - 11;
        this.windowDX = mainX + 100;
        this.windowDY = 14;
    }

    @Override
    protected void init() {
        super.init();
        search = new EditBox(font, leftPos + mainX + SEARCH_X, topPos + 4, 60, 12, Component.translatable("screen.jasm.deck.search"));
        search.setHint(Component.translatable("screen.jasm.deck.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setResponder(text -> scrollRow = 0);
        addRenderableWidget(search);
        addRenderableWidget(JasmButton.icon(() -> sort == GridEntries.Sort.NAME ? SORT_NAME : SORT_AMOUNT, sortLabel(), b -> {
            sort = sort == GridEntries.Sort.NAME ? GridEntries.Sort.AMOUNT : GridEntries.Sort.NAME;
            b.setMessage(sortLabel());
            builtVersion = -1;
        }, leftPos + mainX + 149, topPos + 3, 20, 14));
        addRenderableWidget(JasmButton.icon(() -> ascending ? ARROW_UP : ARROW_DOWN, directionLabel(), b -> {
            ascending = !ascending;
            b.setMessage(directionLabel());
            builtVersion = -1;
        }, leftPos + mainX + 170, topPos + 3, 14, 14));

        settingsButtons.clear();
        place(JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> closeSettings(), 0, 0, 11, 11),
                WINDOW_WIDTH - 16, 5);
        place(JasmButton.text(Component.literal("-"), b -> changePriority(-1), 0, 0, 14, 14), 58, BODY_Y + 27);
        place(JasmButton.text(Component.literal("+"), b -> changePriority(1), 0, 0, 14, 14), 102, BODY_Y + 27);
        modeButton = place(JasmButton.text(modeLabel(), b -> {
            send(new WaferSettings(draft.priority(), !draft.only(), draft.filter()));
            b.setMessage(modeLabel());
        }, 0, 0, 42, 14), 58, BODY_Y + 47);
        place(JasmButton.text(Component.translatable("screen.jasm.deck.settings.done"), b -> closeSettings(), 0, 0, 36, 14),
                WINDOW_WIDTH - 7 - 36, BODY_Y + 87);
        layoutWindow();
    }

    private Button place(Button button, int x, int y) {
        settingsButtons.add(new Placed(button, x, y));
        return button;
    }

    // --- wafer settings ---

    private static final int WINDOW_WIDTH = 176;
    private static final int WINDOW_HEIGHT = 112;
    /** The title bar: grab it to move the window. Everything else sits below it. */
    private static final int TITLE_HEIGHT = 22;
    private static final int BODY_Y = 4;
    private static final int WINDOW_SHADOW = 0x6E000000;
    /** Where the search box starts in the main panel; the title gets the room before it. */
    private static final int SEARCH_X = 86;

    /** The window's left edge on screen, kept fully on screen. */
    private int windowX() {
        return Math.clamp(leftPos + windowDX, 0, Math.max(0, width - WINDOW_WIDTH));
    }

    private int windowY() {
        return Math.clamp(topPos + windowDY, 0, Math.max(0, height - WINDOW_HEIGHT));
    }

    private void layoutWindow() {
        for (Placed placed : settingsButtons) {
            placed.button().setPosition(windowX() + placed.x(), windowY() + placed.y());
            placed.button().visible = editing >= 0;
        }
    }

    private boolean inWindow(double mouseX, double mouseY) {
        return editing >= 0 && mouseX >= windowX() && mouseX < windowX() + WINDOW_WIDTH && mouseY >= windowY()
                && mouseY < windowY() + WINDOW_HEIGHT;
    }

    private Component modeLabel() {
        return Component.translatable(draft.only() ? "screen.jasm.deck.settings.only" : "screen.jasm.deck.settings.prefer");
    }

    private void openSettings(int slot) {
        editing = slot;
        List<DeckStorage.SlotStatus> slots = menu.view().slots();
        draft = slot < slots.size() ? slots.get(slot).settings() : WaferSettings.DEFAULT;
        modeButton.setMessage(modeLabel());
        layoutWindow();
    }

    private void closeSettings() {
        editing = -1;
        grabX = -1;
        layoutWindow();
    }

    private void changePriority(int step) {
        send(new WaferSettings(draft.priority() + step, draft.only(), draft.filter()));
    }

    /** Keeps the edit here and tells the server; the server checks and cleans it up on its side too. */
    private void send(WaferSettings settings) {
        draft = settings;
        ClientPacketDistributor.sendToServer(new DeckPayloads.Configure(menu.containerId, editing, settings));
    }

    /** The filter slot under the mouse, or -1. */
    private int filterSlotAt(double mouseX, double mouseY) {
        int fx = windowX() + 8;
        int fy = windowY() + BODY_Y + 67;
        if (mouseY < fy || mouseY >= fy + 16 || mouseX < fx) {
            return -1;
        }
        int index = (int) ((mouseX - fx) / 18);
        return index < WaferSettings.FILTER_SLOTS && mouseX < fx + index * 18 + 16 ? index : -1;
    }

    private static @Nullable Item filterItem(Identifier id) {
        return BuiltInRegistries.ITEM.get(id).map(holder -> holder.value()).orElse(null);
    }

    /** Holding an item: put it in this filter slot. Empty-handed: clear the slot. The item itself is never used up. */
    private void clickFilter(int index) {
        ItemStack carried = menu.getCarried();
        setFilter(index, carried.isEmpty() ? null : carried.getItem());
    }

    /** Puts {@code item} in filter slot {@code index}, or clears the slot when it is null. */
    public void setFilter(int index, @Nullable Item item) {
        if (editing < 0) {
            return;
        }
        List<Identifier> filter = new ArrayList<>(draft.filter());
        if (item == null) {
            if (index < filter.size()) {
                filter.remove(index);
            }
        } else {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (!filter.contains(id)) {
                if (index < filter.size()) {
                    filter.set(index, id);
                } else {
                    filter.add(id);
                }
            }
        }
        send(new WaferSettings(draft.priority(), draft.only(), filter));
    }

    // --- for item list mods (JEI) ---

    /** An item drawn on this screen outside the normal slots, and where it is drawn. */
    public record ShownItem(ItemStack stack, int x, int y) {}

    /** The settings window's area on screen, with its shadow, while it is open. */
    public Optional<Rect2i> settingsWindowArea() {
        return editing < 0 ? Optional.empty() : Optional.of(new Rect2i(windowX(), windowY(), WINDOW_WIDTH + 3, WINDOW_HEIGHT + 3));
    }

    /** The filter slots on screen while the settings window is open, in order. */
    public List<Rect2i> filterSlotAreas() {
        List<Rect2i> areas = new ArrayList<>();
        if (editing >= 0) {
            for (int i = 0; i < WaferSettings.FILTER_SLOTS; i++) {
                areas.add(new Rect2i(windowX() + 8 + i * 18, windowY() + BODY_Y + 67, 16, 16));
            }
        }
        return areas;
    }

    /** The grid item or filter item under the mouse. */
    public Optional<ShownItem> itemAt(double mouseX, double mouseY) {
        if (inWindow(mouseX, mouseY)) {
            int index = filterSlotAt(mouseX, mouseY);
            Item item = index >= 0 && index < draft.filter().size() ? filterItem(draft.filter().get(index)) : null;
            return item == null ? Optional.empty()
                    : Optional.of(new ShownItem(new ItemStack(item), windowX() + 8 + index * 18, windowY() + BODY_Y + 67));
        }
        GridEntries.Entry<ItemResource> entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            return Optional.empty();
        }
        int column = (int) Math.floor((mouseX - leftPos - gridX) / 18);
        int row = (int) Math.floor((mouseY - topPos - gridY) / 18);
        return Optional.of(new ShownItem(entry.key().toStack(1), leftPos + gridX + column * 18, topPos + gridY + row * 18));
    }

    private Component sortLabel() {
        return Component.translatable(sort == GridEntries.Sort.NAME ? "screen.jasm.deck.sort_name" : "screen.jasm.deck.sort_amount");
    }

    private Component directionLabel() {
        return Component.translatable(ascending ? "screen.jasm.deck.ascending" : "screen.jasm.deck.descending");
    }

    // --- grid contents ---

    private void rebuildIfNeeded() {
        DeckView view = menu.view();
        if (view.version() == builtVersion && search.getValue().equals(builtQuery)) {
            return;
        }
        builtVersion = view.version();
        builtQuery = search.getValue();
        List<GridEntries.Entry<ItemResource>> entries = new ArrayList<>();
        for (Map.Entry<ItemResource, Long> e : view.contents().entrySet()) {
            ItemResource key = e.getKey();
            String id = key.typeHolder().getRegisteredName();
            String modId = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            entries.add(new GridEntries.Entry<>(key, key.getHoverName().getString(), modId, e.getValue()));
        }
        visible = GridEntries.view(entries, SearchQuery.parse(builtQuery), sort, ascending);
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, (visible.size() + COLUMNS - 1) / COLUMNS - ROWS);
    }

    private GridEntries.@Nullable Entry<ItemResource> entryAt(double mouseX, double mouseY) {
        int column = (int) Math.floor((mouseX - leftPos - gridX) / 18);
        int row = (int) Math.floor((mouseY - topPos - gridY) / 18);
        if (!inGrid(mouseX, mouseY) || column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            return null;
        }
        int index = (scrollRow + row) * COLUMNS + column;
        return index < visible.size() ? visible.get(index) : null;
    }

    private boolean inGrid(double mouseX, double mouseY) {
        return mouseX >= leftPos + gridX && mouseX < leftPos + gridX + COLUMNS * 18 && mouseY >= topPos + gridY
                && mouseY < topPos + gridY + ROWS * 18;
    }

    private boolean hasPower() {
        return menu.view().energy() > 0;
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, menu.sideWidth(), menu.sideHeight());
        JasmGui.panel(graphics, x + mainX, y, MAIN_WIDTH, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        drawCharge(graphics, x, y);
        if (editing >= 0 && editing < menu.slots.size()) {
            Slot wafer = menu.slots.get(editing);
            graphics.outline(x + wafer.x - 1, y + wafer.y - 1, 18, 18, JasmGui.ACCENT);
        }
        JasmGui.inset(graphics, x + gridX - 1, y + gridY - 1, COLUMNS * 18, ROWS * 18);
        drawScrollBar(graphics, x, y);
    }

    /** A track beside the grid with a handle; the handle is greyed out when everything fits without scrolling. */
    private void drawScrollBar(GuiGraphicsExtractor graphics, int x, int y) {
        JasmGui.scrollBar(graphics, x + scrollX, y + gridY - 1, SCROLL_WIDTH, SCROLL_HEIGHT + 2, handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
    }

    private int handleOffset() {
        int travel = SCROLL_HEIGHT - HANDLE_HEIGHT;
        return maxScroll() == 0 ? 0 : Math.round(travel * scrollRow / (float) maxScroll());
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        return mouseX >= leftPos + scrollX && mouseX < leftPos + scrollX + SCROLL_WIDTH
                && mouseY >= topPos + gridY - 1 && mouseY < topPos + gridY + SCROLL_HEIGHT + 1;
    }

    /** Scrolls so the handle's middle sits under the mouse. */
    private void scrollToMouse(double mouseY) {
        float travel = SCROLL_HEIGHT - HANDLE_HEIGHT;
        float along = (float) (mouseY - (topPos + gridY) - HANDLE_HEIGHT / 2.0) / travel;
        scrollRow = Math.max(0, Math.min(maxScroll(), Math.round(along * maxScroll())));
    }

    private DeckTier tier() {
        return menu.deck().getItem() instanceof DeckItem deck ? deck.tier() : DeckTier.STARTER;
    }

    private void drawCharge(GuiGraphicsExtractor graphics, int x, int y) {
        int bx = x + imageWidth - 8 - CHARGE_WIDTH;
        JasmGui.bar(graphics, bx - 1, y + statusY, CHARGE_WIDTH + 2, 7, menu.view().energy() / (double) tier().battery());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        rebuildIfNeeded();
        if (editing >= 0 && !(editing < menu.view().slots().size() && menu.view().slots().get(editing).present())) {
            closeSettings();
        }
        // Under the settings window nothing lights up or shows a tooltip.
        boolean overWindow = inWindow(realMouseX, realMouseY);
        int mouseX = overWindow ? -1000 : realMouseX;
        int mouseY = overWindow ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        drawGrid(graphics, mouseX, mouseY);
        if (editing >= 0) {
            graphics.nextStratum();
            drawSettings(graphics, realMouseX, realMouseY, a);
        }
    }

    private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        GridEntries.Entry<ItemResource> hovered = entryAt(mouseX, mouseY);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = (scrollRow + row) * COLUMNS + column;
                if (index >= visible.size()) {
                    continue;
                }
                GridEntries.Entry<ItemResource> entry = visible.get(index);
                int sx = x + gridX + column * 18;
                int sy = y + gridY + row * 18;
                ItemStack stack = entry.key().toStack(1);
                graphics.item(stack, sx, sy);
                graphics.itemDecorations(font, stack, sx, sy, GridEntries.abbreviate(entry.count()));
                if (entry == hovered) {
                    graphics.fill(sx, sy, sx + 16, sy + 16, JasmGui.HOVER);
                }
            }
        }
        if (!hasPower()) {
            graphics.fill(x + gridX - 1, y + gridY - 1, x + gridX + COLUMNS * 18 - 1, y + gridY + ROWS * 18 - 1, JasmGui.SHADE);
            Component text = Component.translatable("screen.jasm.deck.no_power");
            graphics.text(font, text, x + gridX + (COLUMNS * 18 - font.width(text)) / 2, y + gridY + ROWS * 9 - 4, JasmGui.BAD, true);
        }
        int barLeft = x + imageWidth - 8 - CHARGE_WIDTH;
        if (mouseX >= barLeft && mouseX < barLeft + CHARGE_WIDTH && mouseY >= y + statusY && mouseY < y + statusY + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("tooltip.jasm.deck.energy",
                    String.format("%,d", menu.view().energy()), String.format("%,d", tier().battery())), mouseX, mouseY);
        }
        if (hovered != null && menu.getCarried().isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(hovered.key().toStack(1)));
            lines.add(Component.translatable("screen.jasm.deck.stored", String.format("%,d", hovered.count())).withStyle(ChatFormatting.GRAY));
            graphics.setTooltipForNextFrame(font, lines, hovered.key().toStack(1).getTooltipImage(), mouseX, mouseY);
        }
    }

    /** The settings window: a title bar with the wafer and a close button, then priority, filter mode and filter slots. */
    private void drawSettings(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int p = windowX();
        int q = windowY();
        graphics.fill(p + 3, q + 3, p + WINDOW_WIDTH + 3, q + WINDOW_HEIGHT + 3, WINDOW_SHADOW);
        JasmGui.panel(graphics, p, q, WINDOW_WIDTH, WINDOW_HEIGHT);
        ItemStack wafer = menu.slots.get(editing).getItem();
        graphics.item(wafer, p + 6, q + 4);
        Component slotLabel = Component.translatable("screen.jasm.deck.settings.slot", editing + 1);
        int slotLabelX = p + WINDOW_WIDTH - 21 - font.width(slotLabel);
        String name = wafer.getHoverName().getString();
        int room = slotLabelX - 4 - (p + 26);
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        graphics.text(font, name, p + 26, q + 8, JasmGui.TEXT, false);
        graphics.text(font, slotLabel, slotLabelX, q + 8, JasmGui.MUTED, false);
        graphics.fill(p + 4, q + TITLE_HEIGHT, p + WINDOW_WIDTH - 4, q + TITLE_HEIGHT + 1, JasmGui.SELECTED);
        for (Placed placed : settingsButtons) {
            placed.button().extractRenderState(graphics, mouseX, mouseY, a);
        }

        q += BODY_Y;
        for (int i = 0; i < WaferSettings.FILTER_SLOTS; i++) {
            JasmGui.slot(graphics, p + 8 + i * 18, q + 67);
        }

        graphics.text(font, Component.translatable("screen.jasm.deck.settings.priority"), p + 7, q + 31, JasmGui.SUBTEXT, false);
        JasmGui.inset(graphics, p + 74, q + 27, 26, 14);
        String value = draft.priority() > 0 ? "+" + draft.priority() : String.valueOf(draft.priority());
        graphics.text(font, value, p + 74 + (26 - font.width(value)) / 2, q + 31, JasmGui.TEXT, false);
        String when = draft.priority() > 0 ? "screen.jasm.deck.settings.fills_first" : draft.priority() < 0 ? "screen.jasm.deck.settings.fills_last"
                : "screen.jasm.deck.settings.normal";
        graphics.text(font, Component.translatable(when), p + 120, q + 31, JasmGui.MUTED, false);

        graphics.text(font, Component.translatable("screen.jasm.deck.settings.filter"), p + 7, q + 51, JasmGui.SUBTEXT, false);
        graphics.text(font, Component.translatable(draft.only() ? "screen.jasm.deck.settings.nothing_else" : "screen.jasm.deck.settings.others_too"),
                p + 104, q + 51, JasmGui.MUTED, false);
        int hoveredFilter = filterSlotAt(mouseX, mouseY);
        for (int i = 0; i < draft.filter().size(); i++) {
            Item item = filterItem(draft.filter().get(i));
            int fx = p + 8 + i * 18;
            int fy = q + 67;
            if (item != null) {
                graphics.item(new ItemStack(item), fx, fy);
            }
            if (i == hoveredFilter && menu.getCarried().isEmpty()) {
                graphics.setTooltipForNextFrame(font, item != null ? new ItemStack(item).getHoverName()
                        : Component.literal(draft.filter().get(i).toString()), mouseX, mouseY);
            }
        }
        if (hoveredFilter >= 0) {
            int fx = p + 8 + hoveredFilter * 18;
            graphics.fill(fx, q + 67, fx + 16, q + 83, JasmGui.HOVER);
        }
        graphics.text(font, Component.translatable("screen.jasm.deck.settings.hint"), p + 7, q + 91, JasmGui.MUTED, false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        // Long names are cut short so they never run under the search box.
        int room = SEARCH_X - 8 - 3;
        String name = title.getString();
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        graphics.text(font, name, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        long used = 0;
        long capacity = 0;
        long missing = 0;
        long typesUsed = 0;
        long types = 0;
        for (DeckStorage.SlotStatus slot : menu.view().slots()) {
            used += slot.used();
            capacity += slot.capacity();
            missing += slot.fromMissingMods();
            typesUsed += slot.typesUsed();
            types += slot.types();
        }
        // Items from missing mods still take up space; the usage turns red and each wafer's tooltip says how many.
        Component status = Component.translatable("screen.jasm.deck.usage", GridEntries.abbreviate(used), GridEntries.abbreviate(capacity));
        graphics.text(font, status, mainX + 8, statusY, missing > 0 ? JasmGui.BAD : JasmGui.SUBTEXT, false);
        if (types > 0) {
            // Type Wafers: types used, right-aligned before the charge bar, when there is room for both.
            Component typeStatus = Component.translatable("screen.jasm.deck.types", typesUsed, types);
            int right = mainX + MAIN_WIDTH - 8 - CHARGE_WIDTH - 5;
            if (mainX + 8 + font.width(status) + 6 + font.width(typeStatus) <= right) {
                graphics.text(font, typeStatus, right - font.width(typeStatus), statusY, typesUsed >= types ? JasmGui.BAD : JasmGui.SUBTEXT, false);
            }
        }
    }

    /** Wafer slot tooltips add how full the wafer is and whether an Archive protects it. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (hoveredSlot != null && hoveredSlot.index < menu.waferSlots() && hoveredSlot.index < menu.view().slots().size()) {
            DeckStorage.SlotStatus status = menu.view().slots().get(hoveredSlot.index);
            lines.add(Component.translatable("screen.jasm.deck.wafer_used", String.format("%,d", status.used()), String.format("%,d", status.capacity()))
                    .withStyle(ChatFormatting.GRAY));
            if (status.types() > 0) {
                lines.add(Component.translatable("screen.jasm.deck.wafer_types", status.typesUsed(), status.types()).withStyle(ChatFormatting.GRAY));
            }
            WaferSettings settings = status.settings();
            if (settings.priority() != 0) {
                lines.add(Component.translatable("screen.jasm.deck.priority", settings.priority() > 0 ? "+" + settings.priority() : settings.priority())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (!settings.filter().isEmpty() || settings.only()) {
                lines.add(Component.translatable(settings.only() ? "screen.jasm.deck.only" : "screen.jasm.deck.prefers", settings.filter().size())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (status.fromMissingMods() > 0) {
                lines.add(Component.translatable("screen.jasm.deck.missing", String.format("%,d", status.fromMissingMods()))
                        .withStyle(ChatFormatting.RED));
            }
            lines.add(Component.translatable(status.linked() ? "screen.jasm.deck.linked" : "screen.jasm.deck.not_linked")
                    .withStyle(status.linked() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }
        return lines;
    }

    // --- input ---

    /** Below the side panel is outside the screen, so items dropped there fall out as usual. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        if (inWindow(mouseX, mouseY)) {
            return false;
        }
        boolean belowSide = mouseX < left + mainX && mouseY >= top + menu.sideHeight();
        return belowSide || super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /**
     * Grid clicks, holding items: left stores all, right stores one, shift stores all. Empty-handed: left takes a
     * stack, right takes half, shift sends a stack to the inventory.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        // The settings window takes every click that lands on it, so nothing underneath is touched.
        if (inWindow(event.x(), event.y())) {
            for (Placed placed : settingsButtons) {
                if (placed.button().mouseClicked(event, doubleClick)) {
                    return true;
                }
            }
            int filter = filterSlotAt(event.x(), event.y());
            if (filter >= 0) {
                clickFilter(filter);
            } else if (!right && event.y() < windowY() + TITLE_HEIGHT) {
                grabX = (int) event.x() - windowX();
                grabY = (int) event.y() - windowY();
            }
            return true;
        }
        // Right-clicking a wafer (with nothing on the cursor) opens its settings, or switches them to that wafer; the
        // same wafer again closes them.
        Slot clickedSlot = slotAt(event.x(), event.y());
        if (right && clickedSlot != null && clickedSlot.index < menu.waferSlots() && clickedSlot.hasItem() && menu.getCarried().isEmpty()) {
            if (editing == clickedSlot.index) {
                closeSettings();
            } else {
                openSettings(clickedSlot.index);
            }
            return true;
        }
        if (onScrollBar(event.x(), event.y()) && maxScroll() > 0) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        if (inGrid(event.x(), event.y())) {
            if (!menu.getCarried().isEmpty()) {
                ClientPacketDistributor.sendToServer(new DeckPayloads.Insert(menu.containerId, right && !event.hasShiftDown()));
                return true;
            }
            GridEntries.Entry<ItemResource> entry = entryAt(event.x(), event.y());
            if (entry != null) {
                DeckPayloads.ExtractMode mode = event.hasShiftDown() ? DeckPayloads.ExtractMode.TO_INVENTORY
                        : right ? DeckPayloads.ExtractMode.HALF : DeckPayloads.ExtractMode.STACK;
                ClientPacketDistributor.sendToServer(new DeckPayloads.Extract(menu.containerId, entry.key(), mode));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (grabX >= 0) {
            windowDX = Math.clamp((int) event.x() - grabX, 0, Math.max(0, width - WINDOW_WIDTH)) - leftPos;
            windowDY = Math.clamp((int) event.y() - grabY, 0, Math.max(0, height - WINDOW_HEIGHT)) - topPos;
            layoutWindow();
            return true;
        }
        if (inWindow(event.x(), event.y())) {
            return true;
        }
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Releases over the grid or scroll bar are ours; vanilla would treat them as a click on "no slot". */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (grabX >= 0) {
            grabX = -1;
            return true;
        }
        if (inWindow(event.x(), event.y())) {
            return true;
        }
        if (draggingHandle) {
            draggingHandle = false;
            return true;
        }
        if (inGrid(event.x(), event.y()) || onScrollBar(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (inWindow(x, y)) {
            return true;
        }
        if (inGrid(x, y) || onScrollBar(x, y)) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private @Nullable Slot slotAt(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17 && mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    /** While typing in the search box, keys go to the box (so "e" does not close the screen). Esc closes open settings first. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing >= 0 && event.isEscape()) {
            closeSettings();
            return true;
        }
        if (search.isFocused() && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }
}
