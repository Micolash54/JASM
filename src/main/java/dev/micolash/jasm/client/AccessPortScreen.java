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

/** The Access Port screen: the machine it faces, whether a job is using it, its charge, and a box to name it. */
public class AccessPortScreen extends AbstractContainerScreen<AccessPortMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 92;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int NAME_Y = 70;
    private EditBox name;

    public AccessPortScreen(AccessPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        name = new EditBox(font, leftPos + 8, topPos + NAME_Y, WIDTH - 16 - 48, 12, Component.translatable("screen.jasm.port.name"));
        name.setMaxLength(AccessPortMenu.MAX_NAME);
        name.setValue(menu.label());
        name.setHint(Component.translatable("screen.jasm.port.name_hint"));
        addRenderableWidget(name);
        addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.port.rename"), b -> rename(),
                leftPos + WIDTH - 8 - 44, topPos + NAME_Y - 1, 44, 14));
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
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        String shown = font.plainSubstrByWidth(title.getString(), BAR_X - 12 - titleLabelX);
        graphics.text(font, shown, titleLabelX, titleLabelY, JasmGui.TEXT, false);
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
