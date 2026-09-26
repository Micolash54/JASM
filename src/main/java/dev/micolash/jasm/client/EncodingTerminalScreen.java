package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import java.util.List;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The Encoding Terminal screen: card in and out on the left, the ghost grid and what it makes in the middle, the
 * pairing slot on the right, the charge along the top.
 */
public class EncodingTerminalScreen extends AbstractContainerScreen<EncodingTerminalMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = EncodingTerminalMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 40;
    private static final JasmButton.Icon ACCESS = new JasmButton.Icon(Jasm.id("icon/access"), 7, 7);
    private TrustWindow trustWindow;
    private JasmButton accessButton;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final Identifier ARROW_DOWN = Jasm.id("icon/craft_arrow");
    private static final Identifier ARROW_RIGHT = Jasm.id("icon/craft_arrow_right");
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final int MESSAGE_TICKS = 80;
    private int seenCount;
    private int messageTicks;

    public EncodingTerminalScreen(EncodingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = EncodingTerminalMenu.INVENTORY_Y - 10;
        this.seenCount = menu.messageCount();
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.terminal.encode"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_ENCODE),
                leftPos + 108, topPos + 56, 48, 14));
        JasmButton clear = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.terminal.clear"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_CLEAR),
                leftPos + EncodingTerminalMenu.GRID_X + 3 * 18 + 2, topPos + EncodingTerminalMenu.GRID_Y - 1, 11, 11);
        clear.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.clear")));
        addRenderableWidget(clear);
        trustWindow = new TrustWindow(menu, font);
        accessButton = JasmButton.icon(() -> ACCESS, Component.translatable("screen.jasm.terminal.access"),
                b -> trustWindow.toggle(leftPos + 8, topPos + 14), leftPos + BAR_X - 16, topPos + 3, 12, 12);
        accessButton.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.access_hint")));
        addRenderableWidget(accessButton);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (trustWindow.contains(event.x(), event.y())) {
            return trustWindow.mouseClicked(event, doubleClick);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (trustWindow.contains(x, y)) {
            return trustWindow.mouseScrolled(scrollY);
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (trustWindow.isOpen() && trustWindow.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (trustWindow.isOpen() && trustWindow.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    /** Ghost slot {@code i} on screen, for dropping items from JEI. */
    public net.minecraft.client.renderer.Rect2i ghostSlotArea(int i) {
        return new net.minecraft.client.renderer.Rect2i(leftPos + EncodingTerminalMenu.GRID_X + (i % 3) * 18,
                topPos + EncodingTerminalMenu.GRID_Y + (i / 3) * 18, 16, 16);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        // Card in, down to card out; grid across to what it makes.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_DOWN, x + EncodingTerminalMenu.CARD_X + 3, y + 40, 9, 9);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_RIGHT, x + 99, y + EncodingTerminalMenu.PREVIEW_Y + 3, 9, 9);
        JasmGui.bar(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        accessButton.visible = menu.owner();
        boolean over = trustWindow.contains(realMouseX, realMouseY);
        int mouseX = over ? -1000 : realMouseX;
        int mouseY = over ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (trustWindow.isOpen()) {
            graphics.nextStratum();
            trustWindow.draw(graphics, realMouseX, realMouseY, a);
        }
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
        Slot pair = menu.getSlot(EncodingTerminalMenu.SLOT_PAIR);
        if (hoveredSlot == pair && !pair.hasItem() && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, List.of(Component.translatable("screen.jasm.terminal.pair_hint").getVisualOrderText()),
                    mouseX, mouseY);
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

    /** The line under the grid: the last message for a few seconds, otherwise a lack of power or how pairing the Deck is going. */
    private void drawMessage(GuiGraphicsExtractor graphics) {
        Component text = null;
        int color = JasmGui.BAD;
        if (messageTicks > 0 && !menu.message().isEmpty()) {
            text = Component.translatable(menu.message());
            color = menu.message().equals("message.jasm.terminal.encoded") ? JasmGui.GOOD : JasmGui.BAD;
        } else if (!menu.running()) {
            text = Component.translatable("message.jasm.terminal.no_power");
        } else if (menu.getSlot(EncodingTerminalMenu.SLOT_PAIR).hasItem()) {
            text = Component.translatable(menu.paired() ? "screen.jasm.terminal.paired" : "screen.jasm.terminal.pairing");
            color = menu.paired() ? JasmGui.GOOD : JasmGui.BAD;
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
        int room = BAR_X - 20 - titleLabelX;
        String name = title.getString();
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        graphics.text(font, name, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        drawMessage(graphics);
    }
}
