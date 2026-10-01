package dev.micolash.jasm.client;

import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.archive.ArchivePayloads;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.wafer.WaferNumbers;
import dev.micolash.jasm.network.DeckLinkLayout;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Archive screen: the linked wafers (pick one to unlink or recover it) above the link and recovery slots. Drawn
 * with plain fills. Who else may use it is set at an Encoding Terminal on its network.
 */
public class ArchiveScreen extends AbstractContainerScreen<ArchiveMenu> {
    private static final DeckLinkLayout LINK_PANEL = DeckLinkLayout.ARCHIVE;
    private static final int WIDTH = 200;
    private static final int HEIGHT = ArchiveMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 32;
    private static final int SCROLL_WIDTH = 8;
    private static final int HANDLE_HEIGHT = 15;
    private static final int LIST_WIDTH = WIDTH - 16 - SCROLL_WIDTH - 2;
    private static final int SCROLL_X = LIST_X + LIST_WIDTH + 2;
    private static final int ROW_HEIGHT = 12;
    private static final int ROWS = 6;
    private static final int ENERGY_WIDTH = 60;
    private static final int FEEDBACK_Y = LIST_Y + ROWS * ROW_HEIGHT + 4;

    private long selected = -1;
    private int scroll;
    private boolean draggingHandle;
    private Button link;
    private Button unlink;
    private Button recover;
    private Button resetDeck;

    public ArchiveScreen(ArchiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = ArchiveMenu.INVENTORY_Y - 10;
        this.inventoryLabelX = ArchiveMenu.INVENTORY_X;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - WIDTH - LINK_PANEL.width() - 2) / 2 + LINK_PANEL.width() + 2;
        int x = leftPos;
        int y = topPos;
        link = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.link"),
                b -> send(ArchivePayloads.Request.of(menu.containerId, ArchivePayloads.Action.LINK)),
                x + ArchiveMenu.LINK_X + 20, y + ArchiveMenu.ROW_Y - 1, 34, 18));
        unlink = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.unlink"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.UNLINK, selected)),
                x + ArchiveMenu.LINK_X + 56, y + ArchiveMenu.ROW_Y - 1, 44, 18));
        recover = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.recover"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.RECOVER, selected)),
                x + ArchiveMenu.RECOVERY_X + 20, y + ArchiveMenu.ROW_Y - 1, WIDTH - 8 - ArchiveMenu.RECOVERY_X - 20, 18));
        resetDeck = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.reset_deck"),
                b -> send(ArchivePayloads.Request.of(menu.containerId, ArchivePayloads.Action.RESET_DECK)),
                x + LINK_PANEL.panelX() + 8, y + DeckLinkLayout.RESET_Y, LINK_PANEL.width() - 16, 18));
        updateWidgets();
    }

    private void send(ArchivePayloads.Request request) {
        ClientPacketDistributor.sendToServer(request);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
    }

    private void updateWidgets() {
        if (menu.view().entries().stream().noneMatch(e -> e.serial() == selected)) {
            selected = -1;
        }
        link.active = menu.canBackup() && !menu.waferSlots().getItem(ArchiveMenu.LINK_SLOT).isEmpty();
        unlink.active = menu.canBackup() && selected >= 0;
        recover.active = menu.canBackup() && selected >= 0 && !menu.waferSlots().getItem(ArchiveMenu.RECOVERY_SLOT).isEmpty();
        resetDeck.active = menu.canManageDeck() && !menu.defaultDeck();
    }

    private int maxScroll() {
        return Math.max(0, menu.view().entries().size() - ROWS);
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, imageWidth, imageHeight);
        DeckLinkPanel.draw(graphics, font, x, y, LINK_PANEL);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        JasmGui.inset(graphics, x + LIST_X - 1, y + LIST_Y - 1, LIST_WIDTH + 2, ROWS * ROW_HEIGHT + 2);

        drawScrollBar(graphics, x, y);

        int bx = x + imageWidth - 8 - ENERGY_WIDTH;
        JasmGui.bar(graphics, bx - 1, y + 6, ENERGY_WIDTH + 2, 7, menu.view().energy() / (double) menu.tier().energyBuffer());
    }

    /** A track beside the list with a draggable handle, greyed out when everything fits. */
    private void drawScrollBar(GuiGraphicsExtractor graphics, int x, int y) {
        JasmGui.scrollBar(graphics, x + SCROLL_X, y + LIST_Y - 1, SCROLL_WIDTH, trackHeight() + 2, handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
    }

    private int trackHeight() {
        return ROWS * ROW_HEIGHT;
    }

    private int handleOffset() {
        int max = maxScroll();
        return max == 0 ? 0 : Math.round((trackHeight() - HANDLE_HEIGHT) * (scroll / (float) max));
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        double rx = mouseX - leftPos - SCROLL_X;
        double ry = mouseY - topPos - LIST_Y;
        return rx >= 0 && rx < SCROLL_WIDTH && ry >= 0 && ry < trackHeight();
    }

    private void scrollToMouse(double mouseY) {
        double offset = mouseY - topPos - LIST_Y - HANDLE_HEIGHT / 2.0;
        double fraction = Math.clamp(offset / (trackHeight() - HANDLE_HEIGHT), 0.0, 1.0);
        scroll = (int) Math.round(fraction * maxScroll());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        int hovered = rowAt(mouseX, mouseY);
        List<ArchiveService.Entry> entries = menu.view().entries();
        if (entries.isEmpty()) {
            graphics.text(font, Component.translatable("screen.jasm.archive.none"), x + 3, y + 2, JasmGui.MUTED, false);
        }
        for (int row = 0; row < ROWS && scroll + row < entries.size(); row++) {
            ArchiveService.Entry entry = entries.get(scroll + row);
            int ry = y + row * ROW_HEIGHT;
            if (entry.serial() == selected) {
                graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, JasmGui.SELECTED);
            } else if (scroll + row == hovered) {
                graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, JasmGui.HOVER);
            }
            String amount = entry.readable() ? GridEntries.abbreviate(entry.used()) + " / " + GridEntries.abbreviate(entry.capacity()) : "";
            int amountWidth = font.width(amount);
            String number = WaferNumbers.visibleTo(minecraft.player) ? "#" + entry.serial() + " " : "";
            String label = number + (entry.readable() ? entry.name() : Component.translatable("screen.jasm.archive.unreadable").getString());
            graphics.text(font, font.plainSubstrByWidth(label, LIST_WIDTH - amountWidth - 10), x + 3, ry + 2, entry.readable() ? JasmGui.TEXT : JasmGui.BAD, false);
            graphics.text(font, amount, x + LIST_WIDTH - 3 - amountWidth, ry + 2, JasmGui.TEXT, false);
        }
        // The last message lies over the bottom of the list for a few seconds.
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            graphics.nextStratum();
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), x, y + ROWS * ROW_HEIGHT, LIST_WIDTH);
        }
        if (mouseX >= leftPos + imageWidth - 8 - ENERGY_WIDTH && mouseX < leftPos + imageWidth - 8 && mouseY >= topPos + 6 && mouseY < topPos + 13) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.archive.energy", String.format("%,d", menu.view().energy()),
                            String.format("%,d", menu.tier().energyBuffer())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.tier().drainPerTick())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()), mouseX, mouseY);
        }
        if (!menu.view().linkedPlayer().isEmpty() && mouseX >= leftPos + LINK_PANEL.panelX() + 8
                && mouseX < leftPos - 10 && mouseY >= topPos + DeckLinkLayout.MESSAGE_Y
                && mouseY < topPos + DeckLinkLayout.MESSAGE_Y + 20) {
            graphics.setTooltipForNextFrame(font, Component.literal(menu.view().linkedPlayer()), mouseX, mouseY);
        }
        if (hoveredSlot != null && !hoveredSlot.hasItem() && hoveredSlot.index <= ArchiveMenu.RECOVERY_SLOT) {
            graphics.setTooltipForNextFrame(font, Component.translatable(hoveredSlot.index == ArchiveMenu.LINK_SLOT
                    ? "screen.jasm.archive.link_hint" : "screen.jasm.archive.recover_hint"), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component header = Component.translatable("screen.jasm.archive.linked", menu.view().entries().size(), menu.tier().registrations());
        graphics.text(font, header, LIST_X, 20, JasmGui.SUBTEXT, false);
        if (menu.view().energy() == 0) {
            graphics.text(font, Component.translatable("screen.jasm.archive.no_power"), LIST_X, FEEDBACK_Y, JasmGui.BAD, false);
        }
        if (!menu.view().linkedPlayer().isEmpty()) {
            Component linked = Component.translatable("screen.jasm.archive.linked_player");
            graphics.text(font, linked, LINK_PANEL.panelX() + (LINK_PANEL.width() - font.width(linked)) / 2,
                    DeckLinkLayout.MESSAGE_Y, menu.getSlot(ArchiveMenu.DECK_OUT).hasItem() ? JasmGui.GOOD : JasmGui.SUBTEXT, false);
            String playerName = menu.view().linkedPlayer();
            int room = LINK_PANEL.width() - 16;
            String shown = font.width(playerName) <= room ? playerName
                    : font.plainSubstrByWidth(playerName, room - font.width("...")) + "...";
            graphics.text(font, shown, LINK_PANEL.panelX() + (LINK_PANEL.width() - font.width(shown)) / 2,
                    DeckLinkLayout.MESSAGE_Y + 10, JasmGui.MUTED, false);
        } else {
            DeckLinkPanel.message(graphics, font, Component.translatable("screen.jasm.archive.no_deck"), JasmGui.SUBTEXT, LINK_PANEL);
        }
    }

    public net.minecraft.client.renderer.Rect2i deckPanelArea() {
        return new net.minecraft.client.renderer.Rect2i(leftPos + LINK_PANEL.panelX(), topPos, LINK_PANEL.width(), LINK_PANEL.height());
    }

    // --- input ---

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !DeckLinkPanel.contains(mouseX, mouseY, leftPos, topPos, LINK_PANEL)
                && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /** The list row under the mouse, counting scrolled-away rows, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        double rx = mouseX - leftPos - LIST_X;
        double ry = mouseY - topPos - LIST_Y;
        if (rx < 0 || rx >= LIST_WIDTH || ry < 0 || ry >= ROWS * ROW_HEIGHT) {
            return -1;
        }
        int row = scroll + (int) (ry / ROW_HEIGHT);
        return row < menu.view().entries().size() ? row : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (onScrollBar(event.x(), event.y()) && maxScroll() > 0) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        int row = rowAt(event.x(), event.y());
        if (row >= 0) {
            long serial = menu.view().entries().get(row).serial();
            selected = selected == serial ? -1 : serial;
            updateWidgets();
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

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingHandle) {
            draggingHandle = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /** The wheel scrolls over the list or its scroll bar. */
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double rx = x - leftPos - LIST_X;
        double ry = y - topPos - LIST_Y;
        if (rx >= 0 && rx < SCROLL_X + SCROLL_WIDTH - LIST_X && ry >= 0 && ry < ROWS * ROW_HEIGHT) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }
}
