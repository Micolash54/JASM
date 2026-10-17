package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.archive.ArchivePayloads;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.network.LinkWindowCover;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferKind;
import dev.micolash.jasm.wafer.WaferNumbers;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Archive screen: the linked wafers (pick one to unlink or recover it) above the link and recovery slots. A key on
 * the right opens the Override Link window, where the owner picks another player's Deck for this Archive alone. Who
 * else may use the network is set at an Encoding Terminal.
 */
public class ArchiveScreen extends JasmScreen<ArchiveMenu> {
    private JasmFrame frame;
    private static final int WIDTH = 200;
    /** The Deck Link key, in a strip just past the main panel's right edge. */
    private static final int KEY_X = WIDTH;
    private static final JasmButton.Icon LINK_ICON = new JasmButton.Icon(Jasm.id("icon/deck_link"), 12, 12);
    private static final int HEIGHT = ArchiveMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 32;
    private static final int SCROLL_WIDTH = 8;
    private static final int HANDLE_HEIGHT = 15;
    private static final int LIST_WIDTH = WIDTH - 16 - SCROLL_WIDTH - 2;
    /** Centred between the list and the panel's right edge. */
    private static final int SCROLL_X = LIST_X + LIST_WIDTH + 5;
    /** Two lines beside a 16 px wafer: its name, then how full it is. */
    private static final int ROW_HEIGHT = 18;
    private static final int ROWS = 5;
    private static final int ICON_X = 2;
    private static final int TEXT_X = ICON_X + 16 + 4;
    private static final int OVERRIDE_TIP_WIDTH = 170;
    private static final String OVERRIDE_PAGE = "mechanics/override-link.md";
    private static final int ENERGY_WIDTH = 60;
    private static final int FEEDBACK_Y = LIST_Y + ROWS * ROW_HEIGHT + 4;

    private long selected = -1;
    private int scroll;
    private boolean draggingHandle;
    private Button link;
    private Button unlink;
    private Button recover;
    private JasmButton linkKey;
    private @Nullable DeckLinkWindow linkWindow;
    private final Map<WaferTier, ItemStack> waferIcons = new EnumMap<>(WaferTier.class);

    public ArchiveScreen(ArchiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, HEIGHT);
        this.inventoryLabelY = ArchiveMenu.INVENTORY_Y - 10;
        this.inventoryLabelX = ArchiveMenu.INVENTORY_X;
    }

    @Override
    protected LinkWindowCover linkCover() {
        return menu.linkCover();
    }

    @Override
    protected void init() {
        super.init();
        frame = JasmFrame.rounded(new int[]{0, 0, WIDTH, HEIGHT}, JasmGui.sideStrip(KEY_X, 1));
        addHelp(WIDTH - 7, "items/archives.md");
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
        Component linkLabel = Component.translatable("screen.jasm.archive.override_link");
        linkKey = addRenderableWidget(JasmButton.icon(() -> LINK_ICON, linkLabel, b -> toggleLink(), x + KEY_X, y + JasmGui.sideKeyY(0),
                JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        linkKey.setTooltip(Tooltip.create(linkLabel));
        if (linkWindow == null) {
            linkWindow = new DeckLinkWindow(font, menu.getSlot(ArchiveMenu.DECK_IN), menu.getSlot(ArchiveMenu.DECK_OUT), menu.linkCover(),
                    Component.translatable("screen.jasm.archive.reset_deck"),
                    () -> send(ArchivePayloads.Request.of(menu.containerId, ArchivePayloads.Action.RESET_DECK)))
                    .titled(linkLabel).withHelp(OVERRIDE_PAGE);
        }
        updateWidgets();
    }

    private void toggleLink() {
        linkWindow.toggle(leftPos + KEY_X, topPos);
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
        linkWindow.setResetActive(menu.canManageDeck() && !menu.defaultDeck());
        // With nobody else on the network there is no other Deck to link, so the window stays shut.
        linkKey.active = menu.canOverrideLink();
        if (!linkKey.active && linkWindow.isOpen()) {
            linkWindow.close();
        }
        linkKey.setLatched(linkWindow.isOpen());
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
        frame.draw(graphics, x, y);
        // The Deck Link window draws its own two slots.
        for (Slot slot : menu.slots) {
            if (slot.index != ArchiveMenu.DECK_IN && slot.index != ArchiveMenu.DECK_OUT) JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        JasmGui.inset(graphics, x + LIST_X - 1, y + LIST_Y - 1, LIST_WIDTH + 2, ROWS * ROW_HEIGHT + 2);

        drawScrollBar(graphics, x, y);

        int bx = x + WIDTH - 8 - HELP_ROOM - ENERGY_WIDTH;
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
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        linkWindow.sync(leftPos, topPos);
        linkKey.setLatched(linkWindow.isOpen());
        // Under the Deck Link window nothing lights up or shows a tooltip, except its own slots.
        boolean underWindow = linkWindow.hidesMouse(realMouseX, realMouseY);
        int mouseX = underWindow ? -1000 : realMouseX;
        int mouseY = underWindow ? -1000 : realMouseY;
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
                graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT - 1, JasmGui.SELECTED);
            } else if (scroll + row == hovered) {
                graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT - 1, JasmGui.HOVER);
            }
            graphics.item(waferIcon(entry), x + ICON_X, ry + 1);
            int textWidth = LIST_WIDTH - TEXT_X - 3;
            String label = entry.readable() ? entry.name() : Component.translatable("screen.jasm.archive.unreadable").getString();
            graphics.text(font, font.plainSubstrByWidth(label, textWidth), x + TEXT_X, ry + 1, entry.readable() ? JasmGui.TEXT : JasmGui.BAD, false);
            if (entry.readable()) {
                String amount = entry.fluid() ? GridEntries.abbreviateBuckets(entry.used()) + " / " + GridEntries.abbreviate(entry.capacity()) + " B"
                        : GridEntries.abbreviate(entry.used()) + " / " + GridEntries.abbreviate(entry.capacity());
                JasmGui.smallText(graphics, font, Component.literal(amount), x + TEXT_X, ry + 10, JasmGui.SUBTEXT);
            }
        }
        if (hovered >= 0) {
            rowTooltip(graphics, entries.get(hovered), LIST_WIDTH - TEXT_X - 3, mouseX, mouseY);
        }
        // The last message lies over the bottom of the list for a few seconds.
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            graphics.nextStratum();
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), x, y + ROWS * ROW_HEIGHT, LIST_WIDTH);
        }
        if (mouseX >= leftPos + WIDTH - 8 - HELP_ROOM - ENERGY_WIDTH && mouseX < leftPos + WIDTH - 8 - HELP_ROOM && mouseY >= topPos + 6 && mouseY < topPos + 13) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.archive.energy", String.format("%,d", menu.view().energy()),
                            String.format("%,d", menu.tier().energyBuffer())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.tier().drainPerTick())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                    mouseX, mouseY);
        }
        if (hoveredSlot != null && !hoveredSlot.hasItem() && hoveredSlot.index <= ArchiveMenu.RECOVERY_SLOT) {
            graphics.setTooltipForNextFrame(font, Component.translatable(hoveredSlot.index == ArchiveMenu.LINK_SLOT
                    ? "screen.jasm.archive.link_hint"
                    : "screen.jasm.archive.recover_hint"), mouseX, mouseY);
        }
        overrideTooltip(graphics, mouseX, mouseY);
        if (linkWindow.isOpen()) {
            graphics.nextStratum();
            linkWindow.draw(graphics, realMouseX, realMouseY, a,
                    DeckLinkWindow.linkedTo(menu.view().linkedPlayer(), menu.getSlot(ArchiveMenu.DECK_OUT).hasItem()));
        }
    }

    /** The wafer a row stands for, drawn at full size; a size no tier matches shows the smallest of its kind. */
    private ItemStack waferIcon(ArchiveService.Entry entry) {
        WaferKind kind = entry.fluid() ? WaferKind.FLUID : WaferKind.ITEM;
        WaferTier tier = WaferTier.byLimits(kind, entry.capacity(), entry.types()).orElse(entry.fluid() ? WaferTier.FLUID_BASIC : WaferTier.BASIC);
        return waferIcons.computeIfAbsent(tier, t -> new ItemStack(JasmItems.wafer(t)));
    }

    /** The full name when a row cuts it short, and for admins the wafer's number, which the rows no longer show. */
    private void rowTooltip(GuiGraphicsExtractor graphics, ArchiveService.Entry entry, int nameWidth, int mouseX, int mouseY) {
        boolean number = WaferNumbers.visibleTo(minecraft.player);
        boolean cut = entry.readable() && font.width(entry.name()) > nameWidth;
        if (!number && !cut) return;
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (cut) lines.add(Component.literal(entry.name()).getVisualOrderText());
        if (number) lines.add(Component.translatable("screen.jasm.archive.wafer_number", entry.serial()).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /** Why the Override Link key is dimmed, over it. */
    private void overrideTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!linkKey.active && mouseX >= linkKey.getX() && mouseX < linkKey.getX() + linkKey.getWidth()
                && mouseY >= linkKey.getY() && mouseY < linkKey.getY() + linkKey.getHeight()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.archive.override_alone"), OVERRIDE_TIP_WIDTH), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component header = Component.translatable("screen.jasm.archive.linked", menu.view().entries().size(), menu.tier().registrations());
        graphics.text(font, header, LIST_X, 20, JasmGui.SUBTEXT, false);
        if (menu.view().energy() == 0 || ClientNetworkStatus.full(menu.containerId) != null) {
            graphics.text(font, MachineStatusText.noPower(menu.containerId, "screen.jasm.archive.no_power"), LIST_X, FEEDBACK_Y, JasmGui.BAD, false);
        }
    }

    /** Where the Deck Link window is, while it is open, so JEI's item list stays clear of it. */
    public Optional<Rect2i> linkWindowArea() {
        return linkWindow == null ? Optional.empty() : linkWindow.area();
    }

    // --- input ---

    /** Clicks on the Deck Link slots belong to the slots, not to a key behind them. */
    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        return linkWindow != null && linkWindow.overSlot(mouseX, mouseY) ? Optional.empty() : super.getChildAt(mouseX, mouseY);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !linkWindow.contains(mouseX, mouseY) && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /** Escape shuts the Deck Link window first. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (linkWindow.isOpen() && event.isEscape()) {
            linkWindow.close();
            updateWidgets();
            return true;
        }
        return super.keyPressed(event);
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
        if (linkWindow.contains(event.x(), event.y())) {
            return linkWindow.mouseClicked(event, doubleClick) || super.mouseClicked(event, doubleClick);
        }
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
        if (linkWindow.mouseDragged(event, width, height)) {
            return true;
        }
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (linkWindow.mouseReleased()) {
            return true;
        }
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
        if (!linkWindow.contains(x, y) && rx >= 0 && rx < SCROLL_X + SCROLL_WIDTH - LIST_X && ry >= 0 && ry < ROWS * ROW_HEIGHT) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }
}
