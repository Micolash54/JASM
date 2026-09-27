package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Encoding Terminal screen: card in and out on the left, the ghost grid and what it makes in the middle, the
 * pairing slot on the right, the charge along the top. A side panel lists the machines a card can be written for: the
 * Crafting Server first (ordinary crafting cards), then every Access Port. With a port chosen, the grid takes amounts
 * and what the machine gives back goes in a column of three beside it.
 */
public class EncodingTerminalScreen extends AbstractContainerScreen<EncodingTerminalMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = EncodingTerminalMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 40;
    private static final JasmButton.Icon ACCESS = new JasmButton.Icon(Jasm.id("icon/access"), 7, 7);
    private static final JasmButton.Icon MACHINES = new JasmButton.Icon(Jasm.id("icon/machines"), 7, 7);
    private TrustWindow trustWindow;
    private JasmButton accessButton;
    private JasmButton encodeButton;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final Identifier ARROW_DOWN = Jasm.id("icon/craft_arrow");
    private static final Identifier ARROW_RIGHT = Jasm.id("icon/craft_arrow_right");
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final int MESSAGE_TICKS = 80;
    /** The machine panel, to the right of the terminal. */
    private static final int PANEL_X = WIDTH + 2;
    private static final int PANEL_W = 128;
    private static final int LIST_Y = 18;
    private static final int ROW_H = 18;
    private static final int ROWS = (HEIGHT - LIST_Y - 8) / ROW_H;
    /** Whether the machine panel is open; kept while the game runs. */
    private static boolean panelOpen = true;
    private int seenCount;
    private int messageTicks;
    private int scroll;

    public EncodingTerminalScreen(EncodingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = EncodingTerminalMenu.INVENTORY_Y - 10;
        this.seenCount = menu.messageCount();
    }

    @Override
    protected void init() {
        super.init();
        encodeButton = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.terminal.encode"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_ENCODE),
                leftPos + 108, topPos + 56, 48, 14));
        JasmButton clear = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.terminal.clear"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_CLEAR),
                leftPos + EncodingTerminalMenu.GRID_X + 3 * 18 + 2, topPos + EncodingTerminalMenu.GRID_Y - 1, 11, 11);
        clear.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.clear")));
        addRenderableWidget(clear);
        trustWindow = new TrustWindow(menu, font);
        accessButton = JasmButton.icon(() -> ACCESS, Component.translatable("screen.jasm.terminal.access"),
                b -> trustWindow.toggle(leftPos + 8, topPos + 14), leftPos + BAR_X - 15, topPos + 4, 11, 11);
        accessButton.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.access_hint")));
        addRenderableWidget(accessButton);
        JasmButton machines = JasmButton.icon(() -> MACHINES, Component.translatable("screen.jasm.terminal.machines"),
                b -> panelOpen = !panelOpen, leftPos + BAR_X - 28, topPos + 4, 11, 11);
        machines.setTooltip(Tooltip.create(Component.translatable("screen.jasm.terminal.machines_hint")));
        addRenderableWidget(machines);
    }

    // --- the machine panel ---

    /** Where the machine panel sits on screen, while it is open. */
    public Optional<Rect2i> panelArea() {
        return panelOpen ? Optional.of(new Rect2i(leftPos + PANEL_X, topPos, PANEL_W, imageHeight)) : Optional.empty();
    }

    private boolean inPanel(double x, double y) {
        return panelOpen && x >= leftPos + PANEL_X && x < leftPos + PANEL_X + PANEL_W && y >= topPos && y < topPos + imageHeight;
    }

    /** Rows of the list: the Crafting Server, then the machines. */
    private int rows() {
        return 1 + menu.machines().size();
    }

    /** The list row under the mouse, or -1. */
    private int rowAt(double x, double y) {
        int left = leftPos + PANEL_X + 4;
        int top = topPos + LIST_Y;
        if (!panelOpen || x < left || x >= left + PANEL_W - 8 || y < top || y >= top + ROWS * ROW_H) {
            return -1;
        }
        int row = scroll + (int) ((y - top) / ROW_H);
        return row < rows() ? row : -1;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + PANEL_X;
        int y = topPos;
        JasmGui.panel(graphics, x, y, PANEL_W, imageHeight);
        graphics.text(font, Component.translatable("screen.jasm.terminal.machines"), x + 6, y + 6, JasmGui.TEXT, false);
        JasmGui.inset(graphics, x + 3, y + LIST_Y - 1, PANEL_W - 6, ROWS * ROW_H + 2);
        scroll = Math.clamp(scroll, 0, Math.max(0, rows() - ROWS));
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < ROWS && scroll + i < rows(); i++) {
            int row = scroll + i;
            int ry = y + LIST_Y + i * ROW_H;
            boolean chosen;
            String name;
            int color;
            ItemStack icon;
            if (row == 0) {
                chosen = !menu.processing();
                name = Component.translatable("screen.jasm.terminal.crafting_server").getString();
                color = JasmGui.TEXT;
                icon = new ItemStack(JasmBlocks.CRAFTING_SERVER.get());
            } else {
                EncodingTerminalMenu.MachineView view = menu.machines().get(row - 1);
                chosen = view.selected();
                name = view.present() ? view.name() : Component.translatable("screen.jasm.terminal.machine_missing").getString();
                color = view.present() ? JasmGui.TEXT : JasmGui.BAD;
                icon = view.icon();
            }
            if (chosen) {
                graphics.fill(x + 4, ry, x + PANEL_W - 4, ry + ROW_H, JasmGui.SELECTED);
            }
            if (row == hovered) {
                graphics.fill(x + 4, ry, x + PANEL_W - 4, ry + ROW_H, JasmGui.HOVER);
            }
            // A tick box: filled when chosen.
            int bx = x + 7;
            int by = ry + 6;
            graphics.fill(bx, by, bx + 6, by + 6, JasmGui.MUTED);
            graphics.fill(bx + 1, by + 1, bx + 5, by + 5, chosen ? JasmGui.GOOD : JasmGui.SHADE);
            // The machine's block, then its name.
            if (!icon.isEmpty()) {
                graphics.item(icon, bx + 9, ry + 1);
            }
            int textX = bx + 27;
            int room = x + PANEL_W - 6 - textX;
            String shown = font.width(name) > room ? font.plainSubstrByWidth(name, room - font.width("...")) + "..." : name;
            graphics.text(font, shown, textX, ry + 5, color, false);
        }
        if (rows() > ROWS) {
            String more = (scroll + ROWS) + "/" + rows();
            graphics.text(font, more, x + PANEL_W - 6 - font.width(more), y + 6, JasmGui.MUTED, false);
        }
    }

    private void clickRow(int row) {
        if (row == 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_CRAFTING_SERVER);
        } else if (row > 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_MACHINE + row - 1);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (trustWindow.contains(event.x(), event.y())) {
            return trustWindow.mouseClicked(event, doubleClick);
        }
        if (inPanel(event.x(), event.y())) {
            int row = rowAt(event.x(), event.y());
            if (row >= 0) {
                clickRow(row);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The panel sits outside the terminal, but clicking it doesn't throw the held item away. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !inPanel(mouseX, mouseY) && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    /** Processing slot (0-8 the grid, 9-11 the outputs) of a slot of the menu, or -1. */
    private int processingSlot(Slot slot) {
        if (slot.index >= EncodingTerminalMenu.SLOT_GHOST && slot.index < EncodingTerminalMenu.SLOT_PREVIEW) {
            return slot.index - EncodingTerminalMenu.SLOT_GHOST;
        }
        if (slot.index >= EncodingTerminalMenu.SLOT_OUTPUTS && slot.index < EncodingTerminalMenu.SLOT_INVENTORY) {
            return 9 + slot.index - EncodingTerminalMenu.SLOT_OUTPUTS;
        }
        return -1;
    }

    /** Over the machine list it scrolls; over a filled processing slot it changes the amount (by 10 with Shift). */
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (trustWindow.contains(x, y)) {
            return trustWindow.mouseScrolled(scrollY);
        }
        if (inPanel(x, y)) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, rows() - ROWS));
            return true;
        }
        if (menu.processing() && hoveredSlot != null && hoveredSlot.hasItem() && scrollY != 0) {
            int slot = processingSlot(hoveredSlot);
            if (slot >= 0) {
                boolean shift = minecraft.hasShiftDown();
                int op = scrollY > 0 ? (shift ? 2 : 0) : (shift ? 3 : 1);
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, EncodingTerminalMenu.BUTTON_AMOUNT + slot * 4 + op);
                return true;
            }
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

    /** Ghost slot {@code i} on screen (0-8 the grid, 9-11 the outputs), for dropping items from JEI. */
    public Rect2i ghostSlotArea(int i) {
        if (i >= 9) {
            return new Rect2i(leftPos + EncodingTerminalMenu.OUTPUTS_X, topPos + EncodingTerminalMenu.OUTPUTS_Y + (i - 9) * 18, 16, 16);
        }
        return new Rect2i(leftPos + EncodingTerminalMenu.GRID_X + (i % 3) * 18, topPos + EncodingTerminalMenu.GRID_Y + (i / 3) * 18, 16, 16);
    }

    /** How many ghost slots take items right now: the grid, and in processing mode the outputs too. */
    public int ghostSlots() {
        return menu.processing() ? EncodingTerminalBlockEntity.AMOUNTS : 9;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                JasmGui.slot(graphics, x + slot.x, y + slot.y);
            }
        }
        // Card in, down to card out; grid across to what it makes.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_DOWN, x + EncodingTerminalMenu.CARD_X + 3, y + 40, 9, 9);
        int arrowX = menu.processing() ? EncodingTerminalMenu.OUTPUTS_X - 13 : 99;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_RIGHT, x + arrowX, y + EncodingTerminalMenu.PREVIEW_Y + 3, 9, 9);
        JasmGui.bar(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        if (panelOpen) {
            drawPanel(graphics, mouseX, mouseY);
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        accessButton.visible = menu.owner();
        // In processing mode the column of outputs stands where the button was; the button moves right.
        if (menu.processing()) {
            encodeButton.setX(leftPos + 124);
            encodeButton.setWidth(44);
        } else {
            encodeButton.setX(leftPos + 108);
            encodeButton.setWidth(48);
        }
        boolean over = trustWindow.contains(realMouseX, realMouseY);
        int mouseX = over ? -1000 : realMouseX;
        int mouseY = over ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (menu.processing()) {
            drawAmounts(graphics);
        }
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
        if (menu.processing() && hoveredSlot != null && !hoveredSlot.hasItem() && processingSlot(hoveredSlot) >= 9 && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.terminal.output_hint"), 160), mouseX, mouseY);
        }
        int row = rowAt(realMouseX, realMouseY);
        if (row > 0) {
            EncodingTerminalMenu.MachineView view = menu.machines().get(row - 1);
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.literal(view.present() ? view.name() : Component.translatable("screen.jasm.terminal.machine_missing").getString())
                            .getVisualOrderText(),
                    Component.translatable("screen.jasm.terminal.machine_at", view.at().port().getX(), view.at().port().getY(), view.at().port().getZ())
                            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText()), realMouseX, realMouseY);
        } else if (row == 0) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.terminal.crafting_server_hint"), 160),
                    realMouseX, realMouseY);
        }
    }

    /** The amount written on each filled processing slot, over its item. */
    private void drawAmounts(GuiGraphicsExtractor graphics) {
        graphics.nextStratum();
        for (int slot = 0; slot < EncodingTerminalBlockEntity.AMOUNTS; slot++) {
            int amount = menu.amount(slot);
            if (amount <= 0) {
                continue;
            }
            Rect2i area = ghostSlotArea(slot);
            String text = String.valueOf(amount);
            graphics.text(font, text, area.getX() + 17 - font.width(text), area.getY() + 9, 0xFFFFFFFF, true);
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

    /**
     * The line under the grid: the last message for a few seconds, otherwise a lack of power, how pairing the Deck is
     * going, or in processing mode how the amounts work.
     */
    private void drawMessage(GuiGraphicsExtractor graphics) {
        Component text = null;
        int color = JasmGui.BAD;
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            text = notice;
            color = menu.notices().ok() ? JasmGui.GOOD : JasmGui.BAD;
        } else if (messageTicks > 0 && !menu.message().isEmpty()) {
            text = Component.translatable(menu.message());
            color = menu.message().equals("message.jasm.terminal.encoded") ? JasmGui.GOOD : JasmGui.BAD;
        } else if (!menu.running()) {
            text = Component.translatable("message.jasm.terminal.no_power");
        } else if (menu.getSlot(EncodingTerminalMenu.SLOT_PAIR).hasItem()) {
            text = Component.translatable(menu.paired() ? "screen.jasm.terminal.paired" : "screen.jasm.terminal.pairing");
            color = menu.paired() ? JasmGui.GOOD : JasmGui.BAD;
        } else if (menu.processing()) {
            text = Component.translatable("screen.jasm.terminal.processing_hint");
            color = JasmGui.MUTED;
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
        int room = BAR_X - 34 - titleLabelX;
        String name = title.getString();
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        graphics.text(font, name, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        drawMessage(graphics);
    }
}
