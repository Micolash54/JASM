package dev.micolash.jasm.client;

import dev.micolash.jasm.acceptor.AcceptorMode;
import dev.micolash.jasm.acceptor.PowerAcceptorMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/** The Power Acceptor screen: one key per mode, the chosen one sunk in, and a line saying what it does. */
public class PowerAcceptorScreen extends JasmScreen<PowerAcceptorMenu> {
    private static final int WIDTH = 176;
    private static final int MARGIN = 12;
    private static final int TEXT_Y = 22;
    private static final int KEYS_Y = 46;
    private static final int KEY_HEIGHT = 18;
    private static final int KEY_GAP = 3;
    private static final int HEIGHT = KEYS_Y + 3 * KEY_HEIGHT + 2 * KEY_GAP + 12;

    private final JasmButton[] keys = new JasmButton[3];

    public PowerAcceptorScreen(PowerAcceptorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        addHelp(WIDTH - 7, "items/power-acceptor.md");
        for (AcceptorMode mode : AcceptorMode.values()) {
            int i = mode.ordinal();
            keys[i] = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.power_acceptor." + mode.getSerializedName()),
                    b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, i), leftPos + MARGIN,
                    topPos + KEYS_Y + i * (KEY_HEIGHT + KEY_GAP), WIDTH - 2 * MARGIN, KEY_HEIGHT));
        }
        showMode();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        showMode();
    }

    private void showMode() {
        for (int i = 0; i < keys.length; i++) {
            keys[i].setSelected(menu.mode().ordinal() == i);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        Component about = Component.translatable("screen.jasm.power_acceptor.about_" + menu.mode().getSerializedName());
        int line = 0;
        for (FormattedCharSequence wrapped : font.split(about, WIDTH - 2 * MARGIN)) {
            graphics.text(font, wrapped, MARGIN, TEXT_Y + line++ * 10, JasmGui.SUBTEXT, false);
        }
    }
}
