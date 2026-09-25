package dev.micolash.jasm.client;

import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.archive.ArchivePayloads;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.wafer.WaferNumbers;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Archive screen. The Wafers view lists linked wafers (pick one to unlink or recover it) above the link and
 * recovery slots; the owner's Access view lists trusted players with a box to add one. Drawn with plain fills.
 */
public class ArchiveScreen extends AbstractContainerScreen<ArchiveMenu> {
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
    /** How long an action's message stays on screen, in ticks. */
    private static final int FEEDBACK_TICKS = 80;


    private boolean accessView;
    private long selected = -1;
    private int scroll;
    private boolean draggingHandle;
    private Button toggle;
    private Button link;
    private Button unlink;
    private Button recover;
    private Button trust;
    private EditBox name;

    public ArchiveScreen(ArchiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = ArchiveMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;
        toggle = addRenderableWidget(JasmButton.text(Component.empty(), b -> {
            accessView = !accessView;
            scroll = 0;
            draggingHandle = false;
        }, x + WIDTH - 8 - 46, y + 17, 46, 13));
        link = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.link"),
                b -> send(ArchivePayloads.Request.of(menu.containerId, ArchivePayloads.Action.LINK)),
                x + ArchiveMenu.LINK_X + 20, y + ArchiveMenu.ROW_Y - 1, 34, 18));
        unlink = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.unlink"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.UNLINK, selected, noPlayer(), "")),
                x + ArchiveMenu.LINK_X + 56, y + ArchiveMenu.ROW_Y - 1, 44, 18));
        recover = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.recover"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.RECOVER, selected, noPlayer(), "")),
                x + ArchiveMenu.RECOVERY_X + 20, y + ArchiveMenu.ROW_Y - 1, WIDTH - 8 - ArchiveMenu.RECOVERY_X - 20, 18));
        name = addRenderableWidget(new EditBox(font, x + LIST_X + 2, y + LIST_Y + (ROWS - 1) * ROW_HEIGHT, LIST_WIDTH - 60, 11,
                Component.translatable("screen.jasm.archive.player_name")));
        name.setHint(Component.translatable("screen.jasm.archive.player_name").withStyle(ChatFormatting.DARK_GRAY));
        name.setMaxLength(16);
        trust = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.archive.trust"), b -> {
            send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.TRUST, 0, noPlayer(), name.getValue()));
            name.setValue("");
        }, x + LIST_X + LIST_WIDTH - 54, y + LIST_Y + (ROWS - 1) * ROW_HEIGHT - 1, 54, 13));
        updateWidgets();
    }

    private static java.util.UUID noPlayer() {
        return new java.util.UUID(0L, 0L);
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
        ArchivePayloads.State view = menu.view();
        if (!view.owner()) {
            accessView = false;
        }
        if (view.entries().stream().noneMatch(e -> e.serial() == selected)) {
            selected = -1;
        }
        toggle.visible = view.owner();
        toggle.setMessage(Component.translatable(accessView ? "screen.jasm.archive.wafers" : "screen.jasm.archive.access"));
        link.visible = unlink.visible = recover.visible = !accessView;
        link.active = !menu.waferSlots().getItem(ArchiveMenu.LINK_SLOT).isEmpty();
        unlink.active = selected >= 0;
        recover.active = selected >= 0 && !menu.waferSlots().getItem(ArchiveMenu.RECOVERY_SLOT).isEmpty();
        name.visible = trust.visible = accessView;
        trust.active = !name.getValue().isBlank();
        if (!accessView) {
            name.setFocused(false);
        }
    }

    private int rowCount() {
        return accessView ? menu.view().trusted().size() : menu.view().entries().size();
    }

    /** Rows available for the list; the Access view gives its last row to the name box. */
    private int visibleRows() {
        return accessView ? ROWS - 1 : ROWS;
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - visibleRows());
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
        return visibleRows() * ROW_HEIGHT;
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
        if (accessView) {
            List<ArchivePayloads.Trusted> trusted = menu.view().trusted();
            if (trusted.isEmpty()) {
                graphics.text(font, Component.translatable("screen.jasm.archive.nobody_trusted"), x + 3, y + 2, JasmGui.MUTED, false);
            }
            Component remove = Component.translatable("screen.jasm.archive.remove");
            for (int row = 0; row < visibleRows() && scroll + row < trusted.size(); row++) {
                int ry = y + row * ROW_HEIGHT;
                if (scroll + row == hovered) {
                    graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, JasmGui.HOVER);
                }
                graphics.text(font, trusted.get(scroll + row).name(), x + 3, ry + 2, JasmGui.TEXT, false);
                graphics.text(font, remove, x + LIST_WIDTH - 3 - font.width(remove), ry + 2, JasmGui.BAD, false);
            }
        } else {
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
        }
        if (mouseX >= leftPos + imageWidth - 8 - ENERGY_WIDTH && mouseX < leftPos + imageWidth - 8 && mouseY >= topPos + 6 && mouseY < topPos + 13) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.archive.energy", String.format("%,d", menu.view().energy()),
                            String.format("%,d", menu.tier().energyBuffer())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.tier().drainPerTick())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()), mouseX, mouseY);
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
        ArchivePayloads.State view = menu.view();
        Component header = accessView
                ? Component.translatable("screen.jasm.archive.owner", view.ownerName())
                : Component.translatable("screen.jasm.archive.linked", view.entries().size(), menu.tier().registrations());
        graphics.text(font, header, LIST_X, 20, JasmGui.SUBTEXT, false);

        ArchivePayloads.Feedback feedback = menu.feedback();
        if (feedback != null && minecraft.level != null && minecraft.level.getGameTime() - menu.feedbackTime() < FEEDBACK_TICKS) {
            String message = font.plainSubstrByWidth(Component.translatable(feedback.messageKey()).getString(), LIST_WIDTH);
            graphics.text(font, message, LIST_X, FEEDBACK_Y, feedback.ok() ? JasmGui.GOOD : JasmGui.BAD, false);
        } else if (menu.view().energy() == 0) {
            graphics.text(font, Component.translatable("screen.jasm.archive.no_power"), LIST_X, FEEDBACK_Y, JasmGui.BAD, false);
        }
    }

    // --- input ---

    /** The list row under the mouse, counting scrolled-away rows, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        double rx = mouseX - leftPos - LIST_X;
        double ry = mouseY - topPos - LIST_Y;
        if (rx < 0 || rx >= LIST_WIDTH || ry < 0 || ry >= visibleRows() * ROW_HEIGHT) {
            return -1;
        }
        int row = scroll + (int) (ry / ROW_HEIGHT);
        return row < rowCount() ? row : -1;
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
            if (accessView) {
                ArchivePayloads.Trusted player = menu.view().trusted().get(row);
                Component remove = Component.translatable("screen.jasm.archive.remove");
                if (event.x() >= leftPos + LIST_X + LIST_WIDTH - 3 - font.width(remove)) {
                    send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.UNTRUST, 0, player.id(), ""));
                }
            } else {
                long serial = menu.view().entries().get(row).serial();
                selected = selected == serial ? -1 : serial;
            }
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

    /** While typing a name, keys go to the box (so "e" does not close the screen); Enter trusts the player. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (name.isFocused() && name.visible && !event.isEscape()) {
            if (event.isConfirmation() && trust.active) {
                trust.onPress(event);
                return true;
            }
            name.keyPressed(event);
            updateWidgets();
            return true;
        }
        return super.keyPressed(event);
    }
}
