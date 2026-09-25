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
    private static final int LIST_WIDTH = WIDTH - 16;
    private static final int ROW_HEIGHT = 12;
    private static final int ROWS = 6;
    private static final int ENERGY_WIDTH = 60;
    private static final int FEEDBACK_Y = LIST_Y + ROWS * ROW_HEIGHT + 4;
    /** How long an action's message stays on screen, in ticks. */
    private static final int FEEDBACK_TICKS = 80;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int LIST = 0xFF373737;
    private static final int SELECTED = 0xFF5A5A8C;
    private static final int HOVER = 0x30FFFFFF;
    private static final int TEXT = 0xFF404040;
    private static final int LIST_TEXT = 0xFFE0E0E0;
    private static final int WARN = 0xFFFF6060;
    private static final int DONE_TEXT = 0xFF2E7D32;
    private static final int REFUSED_TEXT = 0xFFAA0000;
    private static final int CHARGE = 0xFF3FA34D;

    private boolean accessView;
    private long selected = -1;
    private int scroll;
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
        toggle = addRenderableWidget(Button.builder(Component.empty(), b -> {
            accessView = !accessView;
            scroll = 0;
        }).bounds(x + WIDTH - 8 - 46, y + 17, 46, 13).build());
        link = addRenderableWidget(Button.builder(Component.translatable("screen.jasm.archive.link"),
                b -> send(ArchivePayloads.Request.of(menu.containerId, ArchivePayloads.Action.LINK)))
                .bounds(x + ArchiveMenu.LINK_X + 20, y + ArchiveMenu.ROW_Y - 1, 34, 18).build());
        unlink = addRenderableWidget(Button.builder(Component.translatable("screen.jasm.archive.unlink"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.UNLINK, selected, noPlayer(), "")))
                .bounds(x + ArchiveMenu.LINK_X + 56, y + ArchiveMenu.ROW_Y - 1, 44, 18).build());
        recover = addRenderableWidget(Button.builder(Component.translatable("screen.jasm.archive.recover"),
                b -> send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.RECOVER, selected, noPlayer(), "")))
                .bounds(x + ArchiveMenu.RECOVERY_X + 20, y + ArchiveMenu.ROW_Y - 1, WIDTH - 8 - ArchiveMenu.RECOVERY_X - 20, 18).build());
        name = addRenderableWidget(new EditBox(font, x + LIST_X + 2, y + LIST_Y + (ROWS - 1) * ROW_HEIGHT, LIST_WIDTH - 60, 11,
                Component.translatable("screen.jasm.archive.player_name")));
        name.setHint(Component.translatable("screen.jasm.archive.player_name").withStyle(ChatFormatting.DARK_GRAY));
        name.setMaxLength(16);
        trust = addRenderableWidget(Button.builder(Component.translatable("screen.jasm.archive.trust"), b -> {
            send(new ArchivePayloads.Request(menu.containerId, ArchivePayloads.Action.TRUST, 0, noPlayer(), name.getValue()));
            name.setValue("");
        }).bounds(x + WIDTH - 8 - 54, y + LIST_Y + (ROWS - 1) * ROW_HEIGHT - 1, 54, 13).build());
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
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, PANEL_DARK);
        for (Slot slot : menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, SLOT);
        }
        graphics.fill(x + LIST_X - 1, y + LIST_Y - 1, x + LIST_X + LIST_WIDTH + 1, y + LIST_Y + ROWS * ROW_HEIGHT + 1, PANEL_DARK);
        graphics.fill(x + LIST_X, y + LIST_Y, x + LIST_X + LIST_WIDTH, y + LIST_Y + ROWS * ROW_HEIGHT, LIST);

        int filled = (int) Math.round(ENERGY_WIDTH * Math.min(1.0, menu.view().energy() / (double) menu.tier().energyBuffer()));
        int bx = x + imageWidth - 8 - ENERGY_WIDTH;
        graphics.fill(bx - 1, y + 6, bx + ENERGY_WIDTH + 1, y + 13, PANEL_DARK);
        graphics.fill(bx, y + 7, bx + filled, y + 12, CHARGE);
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
                graphics.text(font, Component.translatable("screen.jasm.archive.nobody_trusted"), x + 3, y + 2, 0xFF9A9A9A, false);
            }
            Component remove = Component.translatable("screen.jasm.archive.remove");
            for (int row = 0; row < visibleRows() && scroll + row < trusted.size(); row++) {
                int ry = y + row * ROW_HEIGHT;
                if (scroll + row == hovered) {
                    graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, HOVER);
                }
                graphics.text(font, trusted.get(scroll + row).name(), x + 3, ry + 2, LIST_TEXT, false);
                graphics.text(font, remove, x + LIST_WIDTH - 3 - font.width(remove), ry + 2, WARN, false);
            }
        } else {
            List<ArchiveService.Entry> entries = menu.view().entries();
            if (entries.isEmpty()) {
                graphics.text(font, Component.translatable("screen.jasm.archive.none"), x + 3, y + 2, 0xFF9A9A9A, false);
            }
            for (int row = 0; row < ROWS && scroll + row < entries.size(); row++) {
                ArchiveService.Entry entry = entries.get(scroll + row);
                int ry = y + row * ROW_HEIGHT;
                if (entry.serial() == selected) {
                    graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, SELECTED);
                } else if (scroll + row == hovered) {
                    graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW_HEIGHT, HOVER);
                }
                String amount = entry.readable() ? GridEntries.abbreviate(entry.used()) + " / " + GridEntries.abbreviate(entry.capacity()) : "";
                int amountWidth = font.width(amount);
                String number = WaferNumbers.visibleTo(minecraft.player) ? "#" + entry.serial() + " " : "";
                String label = number + (entry.readable() ? entry.name() : Component.translatable("screen.jasm.archive.unreadable").getString());
                graphics.text(font, font.plainSubstrByWidth(label, LIST_WIDTH - amountWidth - 10), x + 3, ry + 2, entry.readable() ? LIST_TEXT : WARN, false);
                graphics.text(font, amount, x + LIST_WIDTH - 3 - amountWidth, ry + 2, LIST_TEXT, false);
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
        graphics.text(font, title, titleLabelX, titleLabelY, TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        ArchivePayloads.State view = menu.view();
        Component header = accessView
                ? Component.translatable("screen.jasm.archive.owner", view.ownerName())
                : Component.translatable("screen.jasm.archive.linked", view.entries().size(), menu.tier().registrations());
        graphics.text(font, header, LIST_X, 20, TEXT, false);

        ArchivePayloads.Feedback feedback = menu.feedback();
        if (feedback != null && minecraft.level != null && minecraft.level.getGameTime() - menu.feedbackTime() < FEEDBACK_TICKS) {
            String message = font.plainSubstrByWidth(Component.translatable(feedback.messageKey()).getString(), LIST_WIDTH);
            graphics.text(font, message, LIST_X, FEEDBACK_Y, feedback.ok() ? DONE_TEXT : REFUSED_TEXT, false);
        } else if (menu.view().energy() == 0) {
            graphics.text(font, Component.translatable("screen.jasm.archive.no_power"), LIST_X, FEEDBACK_Y, REFUSED_TEXT, false);
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
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double rx = x - leftPos - LIST_X;
        double ry = y - topPos - LIST_Y;
        if (rx >= 0 && rx < LIST_WIDTH && ry >= 0 && ry < ROWS * ROW_HEIGHT) {
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
