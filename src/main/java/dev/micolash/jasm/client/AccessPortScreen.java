package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftPayloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** The port's status and name, player inventory, and a small upgrade panel on the left. */
public class AccessPortScreen extends AbstractContainerScreen<AccessPortMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = AccessPortMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int NAME_Y = 70;
    private EditBox name;

    public AccessPortScreen(AccessPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelY = AccessPortMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - WIDTH + AccessPortMenu.PANEL_X) / 2 - AccessPortMenu.PANEL_X;
        name = new EditBox(font, leftPos + 8, topPos + NAME_Y, WIDTH - 16 - 48, 12, Component.translatable("screen.jasm.port.name"));
        name.setMaxLength(AccessPortMenu.MAX_NAME);
        name.setValue(menu.label());
        name.setHint(Component.translatable("screen.jasm.port.name_hint"));
        addRenderableWidget(name);
        addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.port.rename"), b -> rename(),
                leftPos + WIDTH - 8 - 44, topPos + NAME_Y - 1, 44, 14));
    }

    public net.minecraft.client.renderer.Rect2i upgradePanelArea() {
        return new net.minecraft.client.renderer.Rect2i(leftPos + AccessPortMenu.PANEL_X, topPos + AccessPortMenu.PANEL_Y,
                AccessPortMenu.PANEL_SIZE, AccessPortMenu.PANEL_SIZE);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        var panel = upgradePanelArea();
        boolean inside = mouseX >= panel.getX() && mouseX < panel.getX() + panel.getWidth()
                && mouseY >= panel.getY() && mouseY < panel.getY() + panel.getHeight();
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
        JasmGui.inset(graphics, leftPos + 8, topPos + 18, WIDTH - 16, 46);
        JasmGui.panel(graphics, leftPos + AccessPortMenu.PANEL_X, topPos + AccessPortMenu.PANEL_Y,
                AccessPortMenu.PANEL_SIZE, AccessPortMenu.PANEL_SIZE);
        for (var slot : menu.slots) JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (hoveredSlot == menu.getSlot(0) && !hoveredSlot.hasItem()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.power_hint"), 180), mouseX, mouseY);
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
        Component machine = Component.translatable("screen.jasm.port.machine", menu.machine());
        graphics.text(font, font.plainSubstrByWidth(machine.getString(), WIDTH - 24), 12, 22, JasmGui.TEXT, false);
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
        graphics.text(font, state, 12, 33, color, false);
        java.util.List<net.minecraft.util.FormattedCharSequence> hint = font.split(Component.translatable("screen.jasm.port.hint"), WIDTH - 24);
        for (int i = 0; i < Math.min(2, hint.size()); i++) {
            graphics.text(font, hint.get(i), 12, 44 + i * 9, JasmGui.SUBTEXT, false);
        }
    }
}
