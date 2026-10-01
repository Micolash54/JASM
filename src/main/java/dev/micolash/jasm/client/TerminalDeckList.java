package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.SearchQuery;
import dev.micolash.jasm.deck.DeckPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Encoding Terminal's Deck tab: everything on the Deck the viewer carries, searchable and scrollable, in the place
 * of their inventory. Items are only picked from it as examples; nothing leaves the Deck.
 */
final class TerminalDeckList {
    static final int COLUMNS = 8;
    static final int ROWS = 4;
    private static final int SCROLL_WIDTH = 10;
    private static final int SCROLL_HEIGHT = ROWS * 18;
    private static final int HANDLE_HEIGHT = 15;

    private final EncodingTerminalMenu menu;
    private final Font font;
    private final EditBox search;
    /** Screen position of the first cell's item. */
    private final int x;
    private final int y;
    private List<GridEntries.Entry<ItemResource>> visible = List.of();
    private int builtVersion = -1;
    private String builtQuery = "";
    private int scrollRow;
    private boolean draggingHandle;

    TerminalDeckList(EncodingTerminalMenu menu, Font font, EditBox search, int x, int y) {
        this.menu = menu;
        this.font = font;
        this.search = search;
        this.x = x;
        this.y = y;
        search.setResponder(text -> scrollRow = 0);
    }

    private void rebuildIfNeeded() {
        if (menu.deckVersion() == builtVersion && search.getValue().equals(builtQuery)) {
            return;
        }
        builtVersion = menu.deckVersion();
        builtQuery = search.getValue();
        List<GridEntries.Entry<ItemResource>> entries = new ArrayList<>();
        for (DeckPayloads.Entry item : menu.deckItems()) {
            String id = item.key().typeHolder().getRegisteredName();
            String modId = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            entries.add(new GridEntries.Entry<>(item.key(), item.key().getHoverName().getString(), modId, item.count()));
        }
        visible = GridEntries.view(entries, SearchQuery.parse(builtQuery), GridEntries.Sort.NAME, true);
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, (visible.size() + COLUMNS - 1) / COLUMNS - ROWS);
    }

    private int scrollX() {
        return x + COLUMNS * 18 + 4;
    }

    /** The cells and the scroll bar. */
    void drawBackground(GuiGraphicsExtractor graphics) {
        rebuildIfNeeded();
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                JasmGui.slot(graphics, x + column * 18, y + row * 18);
            }
        }
        int travel = SCROLL_HEIGHT - 2 - HANDLE_HEIGHT;
        int offset = maxScroll() == 0 ? 0 : Math.round(travel * scrollRow / (float) maxScroll());
        JasmGui.scrollBar(graphics, scrollX(), y - 1, SCROLL_WIDTH, SCROLL_HEIGHT, offset, HANDLE_HEIGHT, maxScroll() > 0);
    }

    /** The items with their counts, or why there are none. */
    void drawItems(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        rebuildIfNeeded();
        GridEntries.Entry<ItemResource> hovered = entryAt(mouseX, mouseY);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = (scrollRow + row) * COLUMNS + column;
                if (index >= visible.size()) {
                    continue;
                }
                GridEntries.Entry<ItemResource> entry = visible.get(index);
                int sx = x + column * 18;
                int sy = y + row * 18;
                ItemStack stack = entry.key().toStack(1);
                graphics.item(stack, sx, sy);
                graphics.itemDecorations(font, stack, sx, sy, "");
                JasmGui.itemCount(graphics, font, GridEntries.abbreviate(entry.count()), sx, sy);
                if (entry == hovered) {
                    graphics.fill(sx, sy, sx + 16, sy + 16, JasmGui.HOVER);
                }
            }
        }
        Component empty = !menu.deckFound() ? Component.translatable("screen.jasm.terminal.deck_none")
                : menu.deckItems().isEmpty() ? Component.translatable("screen.jasm.terminal.deck_empty") : null;
        if (empty != null) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(empty, COLUMNS * 18 - 8);
            int top = y + ROWS * 9 - lines.size() * 9 / 2;
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), x + (COLUMNS * 18 - font.width(lines.get(i))) / 2, top + i * 9, JasmGui.MUTED, false);
            }
        }
    }

    /** The tooltip of the item under the mouse, with how many the Deck holds; empty if none. */
    List<Component> tooltip(double mouseX, double mouseY, List<Component> itemLines) {
        GridEntries.Entry<ItemResource> entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(itemLines);
        lines.add(Component.translatable("screen.jasm.deck.stored", String.format("%,d", entry.count())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("screen.jasm.terminal.deck_pick").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    @Nullable ItemResource keyAt(double mouseX, double mouseY) {
        GridEntries.Entry<ItemResource> entry = entryAt(mouseX, mouseY);
        return entry == null ? null : entry.key();
    }

    GridEntries.@Nullable Entry<ItemResource> entryAt(double mouseX, double mouseY) {
        if (!inGrid(mouseX, mouseY)) {
            return null;
        }
        rebuildIfNeeded();
        // Each cell takes the item and the pixel above and left of it, as the slot frames are drawn.
        int column = Math.clamp((int) Math.floor((mouseX - x + 1) / 18), 0, COLUMNS - 1);
        int row = Math.clamp((int) Math.floor((mouseY - y + 1) / 18), 0, ROWS - 1);
        int index = (scrollRow + row) * COLUMNS + column;
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    boolean inGrid(double mouseX, double mouseY) {
        return mouseX >= x - 1 && mouseX < x - 1 + COLUMNS * 18 && mouseY >= y - 1 && mouseY < y - 1 + ROWS * 18;
    }

    /** The grid and its scroll bar. */
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x - 1 && mouseX < scrollX() + SCROLL_WIDTH && mouseY >= y - 1 && mouseY < y - 1 + SCROLL_HEIGHT;
    }

    void scroll(double amount) {
        scrollRow = Math.clamp(scrollRow - (int) Math.signum(amount), 0, maxScroll());
    }

    /** A press on the scroll bar takes hold of it. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (mouseX >= scrollX() && mouseX < scrollX() + SCROLL_WIDTH && mouseY >= y - 1 && mouseY < y - 1 + SCROLL_HEIGHT) {
            draggingHandle = maxScroll() > 0;
            scrollToMouse(mouseY);
            return true;
        }
        return false;
    }

    boolean mouseDragged(double mouseY) {
        if (draggingHandle) {
            scrollToMouse(mouseY);
            return true;
        }
        return false;
    }

    boolean mouseReleased() {
        boolean was = draggingHandle;
        draggingHandle = false;
        return was;
    }

    private void scrollToMouse(double mouseY) {
        if (maxScroll() == 0) {
            return;
        }
        double fraction = (mouseY - y - HANDLE_HEIGHT / 2.0) / (SCROLL_HEIGHT - 2 - HANDLE_HEIGHT);
        scrollRow = Math.clamp(Math.round(fraction * maxScroll()), 0, maxScroll());
    }
}
