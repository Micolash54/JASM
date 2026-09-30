package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.network.DeckLinkLayout;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** The port's status and name, player inventory, and a small upgrade panel on the left. */
public class AccessPortScreen extends AbstractContainerScreen<AccessPortMenu> {
    private static final int WIDTH = PortUpgradeLayout.MAIN_WIDTH;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int NAME_Y = 70;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 30;
    private static final int LIST_WIDTH = WIDTH - 28;
    private static final int ROW_HEIGHT = 18;
    private static final int ROWS = 2;
    private static final int SCROLL_X = WIDTH - 16;
    private static final int HANDLE_HEIGHT = 12;
    private int scroll;
    private boolean draggingHandle;
    private EditBox name;
    private Button resetDeck;

    public AccessPortScreen(AccessPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, menu.inventoryY() + 58 + 18 + 6);
        inventoryLabelY = menu.inventoryY() - 10;
        inventoryLabelX = (WIDTH - 162) / 2;
    }

    @Override
    protected void init() {
        super.init();
        int panelX = DeckLinkLayout.PORT.panelX();
        leftPos = (width - WIDTH + panelX) / 2 - panelX;
        name = new EditBox(font, leftPos + 8, topPos + NAME_Y, WIDTH - 16 - 48, 12, Component.translatable("screen.jasm.port.name"));
        name.setMaxLength(AccessPortMenu.MAX_NAME);
        name.setValue(menu.label());
        name.setHint(Component.translatable("screen.jasm.port.name_hint"));
        addRenderableWidget(name);
        addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.port.rename"), b -> rename(),
                leftPos + WIDTH - 8 - 44, topPos + NAME_Y - 1, 44, 14));
        var layout = DeckLinkLayout.PORT;
        resetDeck = addRenderableWidget(JasmButton.wrappedText(Component.translatable("screen.jasm.archive.reset_deck"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.RESET_DECK),
                leftPos + layout.panelX() + 8, topPos + DeckLinkLayout.RESET_Y, layout.width() - 16, 22));
        resetDeck.active = menu.canResetDeck();
    }

    public net.minecraft.client.renderer.Rect2i upgradePanelArea() {
        return new net.minecraft.client.renderer.Rect2i(leftPos + AccessPortMenu.PANEL_X, topPos + menu.upgradePanelY(),
                AccessPortMenu.PANEL_WIDTH, AccessPortMenu.PANEL_HEIGHT);
    }

    public java.util.List<net.minecraft.client.renderer.Rect2i> extraAreas() {
        var areas = new java.util.ArrayList<net.minecraft.client.renderer.Rect2i>();
        areas.add(upgradePanelArea());
        areas.add(new net.minecraft.client.renderer.Rect2i(leftPos + PortUpgradeLayout.POWER_PANEL_X, topPos + PortUpgradeLayout.POWER_PANEL_Y,
                PortUpgradeLayout.POWER_PANEL_SIZE, PortUpgradeLayout.POWER_PANEL_SIZE));
        areas.add(new net.minecraft.client.renderer.Rect2i(leftPos + DeckLinkLayout.PORT.panelX(), topPos,
                DeckLinkLayout.PORT.width(), DeckLinkLayout.PORT.height()));
        return areas;
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        boolean inside = extraAreas().stream().anyMatch(panel -> mouseX >= panel.getX() && mouseX < panel.getX() + panel.getWidth()
                && mouseY >= panel.getY() && mouseY < panel.getY() + panel.getHeight());
        return !inside && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    private void rename() {
        ClientPacketDistributor.sendToServer(new CraftPayloads.PortName(menu.containerId, name.getValue().strip()));
        name.setFocused(false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (name.isFocused() && !event.isEscape()) {
            if (event.isConfirmation()) {
                rename();
            } else {
                name.keyPressed(event);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        JasmGui.inset(graphics, leftPos + LIST_X, topPos + LIST_Y, LIST_WIDTH, ROWS * ROW_HEIGHT);
        scroll = Math.clamp(scroll, 0, maxScroll());
        int offset = maxScroll() == 0 ? 0 : Math.round((ROWS * ROW_HEIGHT - HANDLE_HEIGHT) * scroll / (float) maxScroll());
        JasmGui.scrollBar(graphics, leftPos + SCROLL_X, topPos + LIST_Y, 8, ROWS * ROW_HEIGHT,
                offset, HANDLE_HEIGHT, maxScroll() > 0);
        JasmGui.panel(graphics, leftPos + AccessPortMenu.PANEL_X, topPos + menu.upgradePanelY(),
                AccessPortMenu.PANEL_WIDTH, AccessPortMenu.PANEL_HEIGHT);
        JasmGui.panel(graphics, leftPos + PortUpgradeLayout.POWER_PANEL_X, topPos + PortUpgradeLayout.POWER_PANEL_Y,
                PortUpgradeLayout.POWER_PANEL_SIZE, PortUpgradeLayout.POWER_PANEL_SIZE);
        DeckLinkPanel.draw(graphics, font, leftPos, topPos, DeckLinkLayout.PORT);
        for (var slot : menu.slots) JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        for (int row = 0; row < ROWS && scroll + row < menu.machines().size(); row++) {
            var machine = menu.machines().get(scroll + row);
            int y = topPos + LIST_Y + row * ROW_HEIGHT + 1;
            graphics.item(machine.icon(), leftPos + LIST_X + 3, y);
            graphics.text(font, font.plainSubstrByWidth(machine.name().getString(), LIST_WIDTH - 26),
                    leftPos + LIST_X + 23, y + 4, JasmGui.TEXT, false);
        }
        if (menu.machines().isEmpty()) {
            graphics.text(font, Component.translatable("screen.jasm.port.no_machine"), leftPos + LIST_X + 4,
                    topPos + LIST_Y + 5, JasmGui.MUTED, false);
        }
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (resetDeck != null) resetDeck.active = menu.canResetDeck();
        if (mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_WIDTH
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + ROWS * ROW_HEIGHT) {
            int index = scroll + (mouseY - topPos - LIST_Y) / ROW_HEIGHT;
            if (index < menu.machines().size()) graphics.setTooltipForNextFrame(font, menu.machines().get(index).name(), mouseX, mouseY);
        }
        if (hoveredSlot == menu.getSlot(AccessPortMenu.SLOT_DECK_IN) && !hoveredSlot.hasItem()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.deck_hint"), 180), mouseX, mouseY);
        }
        if (hoveredSlot == menu.getSlot(0)) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.power_hint"), 180), mouseX, mouseY);
        }
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (hoveredSlot == menu.getSlot(AccessPortMenu.SLOT_SPEED + i)) {
                graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.transfer.speed_hint"), mouseX, mouseY);
            }
        }
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        String shown = font.plainSubstrByWidth(title.getString(), BAR_X - 12 - titleLabelX);
        graphics.text(font, shown, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component state;
        int color;
        if (!menu.running()) {
            state = Component.translatable("screen.jasm.machine.no_power");
            color = JasmGui.BAD;
        } else if (menu.locked()) {
            state = Component.translatable("screen.jasm.port.in_use");
            color = JasmGui.GOOD;
        } else {
            state = Component.translatable("screen.jasm.port.idle");
            color = JasmGui.MUTED;
        }
        graphics.text(font, Component.translatable("screen.jasm.port.status", state), 8, 18, color, false);
        graphics.text(font, Component.translatable("screen.jasm.port.buffer"), AccessPortMenu.BUFFER_X, AccessPortMenu.BUFFER_Y - 10,
                JasmGui.SUBTEXT, false);
        Component link = Component.translatable(menu.deckLinked() ? "screen.jasm.terminal.linked"
                : menu.defaultDeck() ? "screen.jasm.port.owner_deck" : "screen.jasm.port.override_deck");
        DeckLinkPanel.message(graphics, font, link, menu.deckLinked() ? JasmGui.GOOD : JasmGui.SUBTEXT, DeckLinkLayout.PORT);
    }

    private int maxScroll() { return Math.max(0, menu.machines().size() - ROWS); }

    private boolean onScrollBar(double x, double y) {
        return x >= leftPos + SCROLL_X && x < leftPos + SCROLL_X + 8
                && y >= topPos + LIST_Y && y < topPos + LIST_Y + ROWS * ROW_HEIGHT;
    }

    private void scrollToMouse(double y) {
        double fraction = Math.clamp((y - topPos - LIST_Y - HANDLE_HEIGHT / 2.0)
                / (ROWS * ROW_HEIGHT - HANDLE_HEIGHT), 0.0, 1.0);
        scroll = (int) Math.round(fraction * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= leftPos + LIST_X && x < leftPos + SCROLL_X + 8
                && y >= topPos + LIST_Y && y < topPos + LIST_Y + ROWS * ROW_HEIGHT) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (onScrollBar(event.x(), event.y()) && maxScroll() > 0) {
            draggingHandle = true;
            scrollToMouse(event.y());
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
}
