package dev.micolash.jasm.client;

import dev.micolash.jasm.brain.BrainStatus;
import dev.micolash.jasm.brain.NetworkBrainMenu;
import dev.micolash.jasm.core.BrainBalance;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The Network Brain screen: how many floors its tower has, how many machines its network holds against how many it may,
 * what it is doing, and its charge.
 */
public class NetworkBrainScreen extends JasmScreen<NetworkBrainMenu> {
    private static final int WIDTH = NetworkBrainMenu.WIDTH;
    private static final int HEIGHT = NetworkBrainMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int PAD = 8;
    private static final int INNER_WIDTH = WIDTH - 2 * PAD;
    private static final int FLOORS_Y = 24;
    private static final int BAR_HEIGHT = 8;
    private static final int MACHINES_Y = 40;
    private static final int STATUS_Y = 56;
    private static final int STATUS_HEIGHT = 14;
    private static final int USE_Y = 76;
    private static final int POWER_X = PAD;
    private static final int POWER_Y = NetworkBrainMenu.POWER_Y;
    private static final int POWER_WIDTH = INNER_WIDTH;

    public NetworkBrainScreen(NetworkBrainMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = NetworkBrainMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        addHelp(WIDTH - 7, "items/network-brain.md");
    }

    private static int colour(BrainStatus status) {
        return switch (status) {
            case WORKING -> JasmGui.GOOD;
            case RESTING -> JasmGui.WARN;
            case NO_POWER -> JasmGui.BAD;
        };
    }

    private static String statusKey(BrainStatus status) {
        return "screen.jasm.brain.status." + status.name().toLowerCase(Locale.ROOT);
    }

    /** The full status line, or its short form where the full one doesn't fit in the well. */
    private Component statusText() {
        String key = statusKey(menu.status());
        Component full = Component.translatable(key);
        if (font.width(full) <= INNER_WIDTH - 8 || !Language.getInstance().has(key + ".short")) {
            return full;
        }
        return Component.translatable(key + ".short");
    }

    private boolean shortened() {
        return font.width(Component.translatable(statusKey(menu.status()))) > INNER_WIDTH - 8;
    }

    private static boolean within(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
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
        JasmGui.inset(graphics, x + PAD, y + STATUS_Y, INNER_WIDTH, STATUS_HEIGHT);
        JasmGui.bar(graphics, x + POWER_X, y + POWER_Y, POWER_WIDTH, BAR_HEIGHT, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (within(mouseX, mouseY, leftPos + POWER_X, topPos + POWER_Y, POWER_WIDTH, BAR_HEIGHT)) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
        if (within(mouseX, mouseY, leftPos + PAD, topPos + USE_Y, INNER_WIDTH, font.lineHeight)) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.brain.use.tower"), 180), mouseX, mouseY);
        }
        if (shortened() && within(mouseX, mouseY, leftPos + PAD, topPos + STATUS_Y, INNER_WIDTH, STATUS_HEIGHT)) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable(statusKey(menu.status())), 180), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);

        graphics.text(font, NetworkBrainMenu.floorsText(menu.floors()), PAD, FLOORS_Y, JasmGui.TEXT, false);

        boolean over = menu.machineCount() > menu.machineLimit();
        graphics.text(font, Component.translatable("screen.jasm.brain.machines", menu.machineCount(), BrainBalance.shown(menu.machineLimit())), PAD, MACHINES_Y,
                over ? JasmGui.BAD : JasmGui.TEXT, false);

        graphics.text(font, statusText(), PAD + 4, STATUS_Y + 3, colour(menu.status()), false);

        graphics.text(font, Component.translatable("screen.jasm.brain.use", String.format("%,d", menu.drain())), PAD, USE_Y, JasmGui.SUBTEXT, false);
    }
}
