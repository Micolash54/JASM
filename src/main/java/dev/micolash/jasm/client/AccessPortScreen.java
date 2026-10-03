package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The port's status, machines and name, its item buffer and the player inventory. A column on the right holds the side
 * keys (Deck Link and blocking mode) above the upgrade slots. The Deck Link key opens a window with the link slots.
 */
public class AccessPortScreen extends JasmScreen<AccessPortMenu> {
    private static final int WIDTH = AccessPortMenu.WIDTH;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int NAME_Y = 106;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 30;
    private static final int LIST_WIDTH = WIDTH - 28;
    private static final int ROW_HEIGHT = 18;
    private static final int ROWS = 4;
    private static final int SCROLL_X = WIDTH - 15;
    private static final int HANDLE_HEIGHT = 12;
    private static final int KEY_X = PortUpgradeLayout.KEY_X;
    private static final JasmButton.Icon LINK = new JasmButton.Icon(Jasm.id("icon/deck_link"), 12, 12);
    private static final JasmButton.Icon BLOCKING_ON = new JasmButton.Icon(Jasm.id("icon_blocking_on"), 12, 12);
    private static final JasmButton.Icon BLOCKING_OFF = new JasmButton.Icon(Jasm.id("icon_blocking_off"), 12, 12);
    private int scroll;
    private boolean draggingHandle;
    private EditBox name;
    private Button rename;
    private JasmButton blocking;
    private JasmButton link;
    private JasmFrame frame;
    private @Nullable DeckLinkWindow linkWindow;

    public AccessPortScreen(AccessPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, menu.inventoryY() + 84);
        inventoryLabelY = menu.inventoryY() - 10;
        inventoryLabelX = (WIDTH - 162) / 2;
    }

    @Override
    protected void init() {
        super.init();
        frame = JasmFrame.rounded(new int[]{0, 0, WIDTH, imageHeight}, PortUpgradeLayout.column(AccessPortMenu.SIDE_KEYS));
        name = new JasmField(font, leftPos + 8, topPos + NAME_Y, WIDTH - 16 - 48, 12, Component.translatable("screen.jasm.port.name"));
        name.setMaxLength(AccessPortMenu.MAX_NAME);
        name.setValue(menu.label());
        name.setHint(Component.translatable("screen.jasm.port.name_hint"));
        addRenderableWidget(name);
        rename = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.port.rename"), b -> rename(),
                leftPos + WIDTH - 8 - 44, topPos + NAME_Y - 1, 44, 14));
        Component linkLabel = Component.translatable("screen.jasm.deck_link");
        link = addRenderableWidget(JasmButton.icon(() -> LINK, linkLabel, b -> toggleLink(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        link.setTooltip(Tooltip.create(linkLabel));
        blocking = addRenderableWidget(JasmButton.icon(() -> menu.blockingMode() ? BLOCKING_ON : BLOCKING_OFF, blockingLabel(),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.TOGGLE_BLOCKING),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(1), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        blocking.setTooltip(blockingTooltip());
        if (linkWindow == null) {
            linkWindow = new DeckLinkWindow(font, menu.getSlot(AccessPortMenu.SLOT_DECK_IN), menu.getSlot(AccessPortMenu.SLOT_DECK_OUT),
                    menu.linkCover(), Component.translatable("screen.jasm.archive.reset_deck"),
                    () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.RESET_DECK));
        }
        link.setLatched(linkWindow.isOpen());
    }

    private void toggleLink() {
        linkWindow.toggle(leftPos + KEY_X, topPos);
        link.setLatched(linkWindow.isOpen());
        if (linkWindow.isOpen()) name.setFocused(false);
    }

    /** Where the Deck Link window is, while it is open, so JEI's item list stays clear of it. */
    public List<Rect2i> extraAreas() {
        return linkWindow == null ? List.of() : linkWindow.area().map(List::of).orElse(List.of());
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !linkWindow.contains(mouseX, mouseY) && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    private void rename() {
        ClientPacketDistributor.sendToServer(new CraftPayloads.PortName(menu.containerId, name.getValue().strip()));
        name.setFocused(false);
    }

    private Component blockingLabel() {
        return Component.translatable("screen.jasm.port.blocking", Component.translatable(menu.blockingMode()
                ? "screen.jasm.port.blocking_on"
                : "screen.jasm.port.blocking_off"));
    }

    private Tooltip blockingTooltip() {
        return Tooltip.create(blockingLabel().copy().append("\n").append(Component.translatable("screen.jasm.port.blocking_hint")));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape shuts the Deck Link window first, then the screen.
        if (linkWindow.isOpen() && event.isEscape()) {
            linkWindow.close();
            link.setLatched(false);
            return true;
        }
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
        frame.draw(graphics, leftPos, topPos);
        JasmGui.divider(graphics, leftPos + KEY_X, topPos + PortUpgradeLayout.dividerY(AccessPortMenu.SIDE_KEYS), JasmGui.SIDE_KEY_WIDTH);
        JasmGui.inset(graphics, leftPos + LIST_X, topPos + LIST_Y, LIST_WIDTH, ROWS * ROW_HEIGHT);
        scroll = Math.clamp(scroll, 0, maxScroll());
        int offset = maxScroll() == 0 ? 0 : Math.round((ROWS * ROW_HEIGHT - HANDLE_HEIGHT) * scroll / (float) maxScroll());
        JasmGui.scrollBar(graphics, leftPos + SCROLL_X, topPos + LIST_Y, 8, ROWS * ROW_HEIGHT,
                offset, HANDLE_HEIGHT, maxScroll() > 0);
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
        // The Deck Link window draws its own two slots.
        for (var slot : menu.slots) {
            if (slot.isActive() && slot.index != AccessPortMenu.SLOT_DECK_IN && slot.index != AccessPortMenu.SLOT_DECK_OUT) {
                JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        linkWindow.sync(leftPos, topPos);
        // Under the Deck Link window nothing lights up or shows a tooltip, except its own slots.
        boolean hidden = linkWindow.hidesMouse(realMouseX, realMouseY);
        int mouseX = hidden ? -1000 : realMouseX;
        int mouseY = hidden ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        linkWindow.setResetActive(menu.canResetDeck());
        if (blocking != null) blocking.setLatched(menu.blockingMode());
        if (blocking != null && !blocking.getMessage().equals(blockingLabel())) {
            blocking.setMessage(blockingLabel());
            blocking.setTooltip(blockingTooltip());
        }
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
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH
                && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
        if (linkWindow.isOpen()) {
            graphics.nextStratum();
            linkWindow.draw(graphics, realMouseX, realMouseY, a, DeckLinkWindow.linkedTo(menu.linkedPlayer(), menu.deckLinked()));
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        String shown = font.plainSubstrByWidth(title.getString(), BAR_X - 12 - titleLabelX);
        graphics.text(font, shown, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        Component state;
        int color;
        if (!menu.running()) {
            state = MachineStatusText.noPower(menu.containerId, "screen.jasm.machine.no_power", font,
                    WIDTH - 16 - font.width(Component.translatable("screen.jasm.port.status", "")));
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
        if (!linkWindow.contains(x, y) && x >= leftPos + LIST_X && x < leftPos + SCROLL_X + 8
                && y >= topPos + LIST_Y && y < topPos + LIST_Y + ROWS * ROW_HEIGHT) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
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
}
