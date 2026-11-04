package dev.micolash.jasm.client;

import dev.micolash.jasm.tether.NetworkTetherMenu;
import dev.micolash.jasm.tether.TetherStatus;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The Network Tether screen: the operator slot, how many chunks the Tether holds, then what the operator says in the
 * Chip Workshop's speech bubble, coloured by how it stands, then the inventory. With nobody seated a grey line says who
 * goes in the slot. The power the operator pays sits on the title row, as on the Relay Chatterbox. When the server's
 * limit cuts the Tether short, the chunk count reads "64 of 90" with "Server limit" under it.
 *
 * <p>Layout, in GUI pixels: 176 wide, title on row 6, slot 20 to 36, bubble from 42 (its tail from 39), one or two lines
 * wrapped to 154 pixels, so at most 42 to 68; inventory label 75, inventory from 86.
 */
public class NetworkTetherScreen extends JasmScreen<NetworkTetherMenu> {
    private static final int WIDTH = NetworkTetherMenu.WIDTH;
    private static final int HEIGHT = NetworkTetherMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BUBBLE_X = 7;
    private static final int BUBBLE_Y = 42;
    private static final int BUBBLE_PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BUBBLE_LINES = 2;
    private static final int TEXT_WIDTH = WIDTH - 2 * BUBBLE_X - 2 * BUBBLE_PAD;
    /** The text is always dark; the bubble's fill and edge follow the status, as on the Relay Chatterbox. */
    private static final int BUBBLE_TEXT = 0xFF1E1E2E;
    private static final int CALM_FILL = 0xFFCDD6F4;
    private static final int CALM_EDGE = 0xFF9399B2;
    private static final int WARN_FILL = 0xFFF9E2AF;
    private static final int WARN_EDGE = 0xFFB8A06E;
    private static final int BAD_FILL = 0xFFF5C2CB;
    private static final int BAD_EDGE = 0xFFB5868F;

    public NetworkTetherScreen(NetworkTetherMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelX = NetworkTetherMenu.INVENTORY_X;
        this.inventoryLabelY = NetworkTetherMenu.INVENTORY_Y - 11;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        if (!menu.operator().isEmpty()) {
            TetherStatus status = menu.status();
            RelayChatterboxScreen.bubble(graphics, leftPos + BUBBLE_X, topPos + BUBBLE_Y, speech().size(), fill(status), edge(status));
        }
    }

    /** The operator's line, wrapped, at most {@link #BUBBLE_LINES} lines. */
    private List<FormattedCharSequence> speech() {
        List<FormattedCharSequence> lines = font.split(Component.translatable(line(menu.status())), TEXT_WIDTH);
        return lines.subList(0, Math.min(BUBBLE_LINES, lines.size()));
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component power = Component.translatable("screen.jasm.network_tether.power", String.format("%,d", menu.powerUse()));
        JasmGui.smallText(graphics, font, power, imageWidth - 8 - JasmGui.smallWidth(font, power), titleLabelY + 1, JasmGui.SUBTEXT);
        int rowY = NetworkTetherMenu.SLOT_Y + 4;
        Component operator = Component.translatable("screen.jasm.network_tether.operator");
        graphics.text(font, operator, NetworkTetherMenu.OPERATOR_X + 20, rowY, JasmGui.SUBTEXT, false);
        int limitedOf = menu.limitedOf();
        if (limitedOf > 0) {
            Component chunks = Component.translatable("screen.jasm.network_tether.chunks_of", menu.chunks(), limitedOf);
            Component limit = Component.translatable("screen.jasm.network_tether.server_limit");
            int room = imageWidth - 8 - (NetworkTetherMenu.OPERATOR_X + 20 + font.width(operator) + 4);
            if (font.width(chunks) <= room) {
                graphics.text(font, chunks, imageWidth - 8 - font.width(chunks), NetworkTetherMenu.SLOT_Y, JasmGui.TEXT, false);
            } else {
                JasmGui.smallText(graphics, font, chunks, imageWidth - 8 - JasmGui.smallWidth(font, chunks), NetworkTetherMenu.SLOT_Y + 2,
                        JasmGui.TEXT);
            }
            graphics.text(font, limit, imageWidth - 8 - font.width(limit), NetworkTetherMenu.SLOT_Y + 10, JasmGui.WARN, false);
        } else {
            Component chunks = Component.translatable("screen.jasm.network_tether.chunks", menu.chunks());
            graphics.text(font, chunks, imageWidth - 8 - font.width(chunks), rowY, JasmGui.TEXT, false);
        }
        if (menu.operator().isEmpty()) {
            TetherStatus shown = menu.status() == TetherStatus.DISABLED ? TetherStatus.DISABLED : TetherStatus.EMPTY;
            List<FormattedCharSequence> hint = font.split(Component.translatable(line(shown)), TEXT_WIDTH);
            for (int i = 0; i < Math.min(BUBBLE_LINES, hint.size()); i++) {
                graphics.text(font, hint.get(i), NetworkTetherMenu.OPERATOR_X, BUBBLE_Y + BUBBLE_PAD - 1 + i * LINE_HEIGHT, JasmGui.SUBTEXT, false);
            }
            return;
        }
        List<FormattedCharSequence> parts = speech();
        for (int i = 0; i < parts.size(); i++) {
            FormattedCharSequence part = parts.get(i);
            graphics.text(font, part, (imageWidth - font.width(part)) / 2, BUBBLE_Y + BUBBLE_PAD - 1 + i * LINE_HEIGHT, BUBBLE_TEXT, false);
        }
    }

    /** What the operator says. */
    private static String line(TetherStatus status) {
        return "screen.jasm.network_tether.status." + status.name().toLowerCase(Locale.ROOT);
    }

    /** Lavender while on watch or standing by, yellow while the chunks are about to go, red once they have gone or Tethers are off. */
    private static int fill(TetherStatus status) {
        return switch (status) {
            case NO_POWER -> WARN_FILL;
            case ASLEEP, DISABLED -> BAD_FILL;
            default -> CALM_FILL;
        };
    }

    private static int edge(TetherStatus status) {
        return switch (status) {
            case NO_POWER -> WARN_EDGE;
            case ASLEEP, DISABLED -> BAD_EDGE;
            default -> CALM_EDGE;
        };
    }
}
