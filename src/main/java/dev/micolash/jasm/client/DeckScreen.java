package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.SearchQuery;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.deck.DeckView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck screen: wafer slots on top, then charge and wafer status, a searchable, scrollable grid of everything
 * on the Deck's wafers, and the player's inventory. Drawn with plain fills; proper art comes later.
 */
public class DeckScreen extends AbstractContainerScreen<DeckMenu> {
    private static final int WIDTH = 190;
    private static final int COLUMNS = 9;
    private static final int ROWS = 4;
    private static final int GRID_X = 8;
    private static final int CHARGE_WIDTH = 60;
    private static final int SCROLL_X = GRID_X + COLUMNS * 18 + 3;
    private static final int SCROLL_WIDTH = 10;
    private static final int SCROLL_HEIGHT = ROWS * 18 - 2;
    private static final int HANDLE_HEIGHT = 15;

    private static final JasmButton.Icon SORT_NAME = new JasmButton.Icon(Jasm.id("icon/sort_name"), 7, 5);
    private static final JasmButton.Icon SORT_AMOUNT = new JasmButton.Icon(Jasm.id("icon/sort_amount"), 7, 5);
    private static final JasmButton.Icon ARROW_UP = new JasmButton.Icon(Jasm.id("icon/arrow_up"), 5, 3);
    private static final JasmButton.Icon ARROW_DOWN = new JasmButton.Icon(Jasm.id("icon/arrow_down"), 5, 3);

    private EditBox search;
    private GridEntries.Sort sort = GridEntries.Sort.NAME;
    private boolean ascending = true;
    private int scrollRow;
    private int builtVersion = -1;
    private String builtQuery = "";
    private List<GridEntries.Entry<ItemResource>> visible = List.of();
    private boolean draggingHandle;
    /** Grid and status line sit just above the inventory, which is lower on Decks with a third row of wafers. */
    private final int gridY;
    private final int statusY;

    public DeckScreen(DeckMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, menu.inventoryY() + 58 + 18 + 6);
        this.inventoryLabelY = menu.inventoryY() - 10;
        this.gridY = menu.inventoryY() - 12 - ROWS * 18;
        this.statusY = gridY - 11;
    }

    @Override
    protected void init() {
        super.init();
        search = new EditBox(font, leftPos + 80, topPos + 4, 66, 12, Component.translatable("screen.jasm.deck.search"));
        search.setHint(Component.translatable("screen.jasm.deck.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setResponder(text -> scrollRow = 0);
        addRenderableWidget(search);
        addRenderableWidget(JasmButton.icon(() -> sort == GridEntries.Sort.NAME ? SORT_NAME : SORT_AMOUNT, sortLabel(), b -> {
            sort = sort == GridEntries.Sort.NAME ? GridEntries.Sort.AMOUNT : GridEntries.Sort.NAME;
            b.setMessage(sortLabel());
            builtVersion = -1;
        }, leftPos + 149, topPos + 3, 20, 14));
        addRenderableWidget(JasmButton.icon(() -> ascending ? ARROW_UP : ARROW_DOWN, directionLabel(), b -> {
            ascending = !ascending;
            b.setMessage(directionLabel());
            builtVersion = -1;
        }, leftPos + 170, topPos + 3, 14, 14));
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
        int column = (int) Math.floor((mouseX - leftPos - GRID_X) / 18);
        int row = (int) Math.floor((mouseY - topPos - gridY) / 18);
        if (!inGrid(mouseX, mouseY) || column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            return null;
        }
        int index = (scrollRow + row) * COLUMNS + column;
        return index < visible.size() ? visible.get(index) : null;
    }

    private boolean inGrid(double mouseX, double mouseY) {
        return mouseX >= leftPos + GRID_X && mouseX < leftPos + GRID_X + COLUMNS * 18 && mouseY >= topPos + gridY
                && mouseY < topPos + gridY + ROWS * 18;
    }

    private boolean hasPower() {
        return menu.view().energy() >= JasmConfig.DECK_TRANSFER_BASE_COST.getAsInt() + JasmConfig.DECK_TRANSFER_PER_ITEM_COST.getAsInt();
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        JasmGui.inset(graphics, x + GRID_X - 1, y + gridY - 1, COLUMNS * 18, ROWS * 18);
        drawScrollBar(graphics, x, y);
        drawCharge(graphics, x, y);
    }

    /** A track beside the grid with a handle; the handle is greyed out when everything fits without scrolling. */
    private void drawScrollBar(GuiGraphicsExtractor graphics, int x, int y) {
        JasmGui.scrollBar(graphics, x + SCROLL_X, y + gridY - 1, SCROLL_WIDTH, SCROLL_HEIGHT + 2, handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
    }

    private int handleOffset() {
        int travel = SCROLL_HEIGHT - HANDLE_HEIGHT;
        return maxScroll() == 0 ? 0 : Math.round(travel * scrollRow / (float) maxScroll());
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        return mouseX >= leftPos + SCROLL_X && mouseX < leftPos + SCROLL_X + SCROLL_WIDTH
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
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        rebuildIfNeeded();
        super.extractContents(graphics, mouseX, mouseY, a);
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
                int sx = x + GRID_X + column * 18;
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
            graphics.fill(x + GRID_X - 1, y + gridY - 1, x + GRID_X + COLUMNS * 18 - 1, y + gridY + ROWS * 18 - 1, JasmGui.SHADE);
            Component text = Component.translatable("screen.jasm.deck.no_power");
            graphics.text(font, text, x + GRID_X + (COLUMNS * 18 - font.width(text)) / 2, y + gridY + ROWS * 9 - 4, JasmGui.BAD, true);
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

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        long used = 0;
        long capacity = 0;
        long missing = 0;
        for (DeckStorage.SlotStatus slot : menu.view().slots()) {
            used += slot.used();
            capacity += slot.capacity();
            missing += slot.fromMissingMods();
        }
        // Items from missing mods still take up space; the usage turns red and each wafer's tooltip says how many.
        Component status = Component.translatable("screen.jasm.deck.usage", GridEntries.abbreviate(used), GridEntries.abbreviate(capacity));
        graphics.text(font, status, 8, statusY, missing > 0 ? JasmGui.BAD : JasmGui.SUBTEXT, false);
    }

    /** Wafer slot tooltips add how full the wafer is and whether an Archive protects it. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (hoveredSlot != null && hoveredSlot.index < menu.waferSlots() && hoveredSlot.index < menu.view().slots().size()) {
            DeckStorage.SlotStatus status = menu.view().slots().get(hoveredSlot.index);
            lines.add(Component.translatable("screen.jasm.deck.wafer_used", String.format("%,d", status.used()), String.format("%,d", status.capacity()))
                    .withStyle(ChatFormatting.GRAY));
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

    /**
     * Grid clicks, holding items: left stores all, right stores one, shift stores all. Empty-handed: left takes a
     * stack, right takes half, shift sends a stack to the inventory.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
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
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Releases over the grid or scroll bar are ours; vanilla would treat them as a click on "no slot". */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
        if (inGrid(x, y) || onScrollBar(x, y)) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    /** While typing in the search box, keys go to the box (so "e" does not close the screen). */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search.isFocused() && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }
}
