package dev.micolash.jasm.client;

import dev.micolash.jasm.battery.BatteryMenu;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.ReadingFit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/**
 * The Battery screen: the charge of the whole battery across a bar, the power going in and out under it, and how many
 * blocks share it. Every block of a battery shows the same. Above all that, the battery's charging slot with the item's
 * charge under it, and below, the player's inventory. A number is written out in full for as long as its line has room;
 * past that the longest one is abbreviated, and hovering it shows the exact figure.
 */
public class BatteryScreen extends JasmScreen<BatteryMenu> {
    private static final int WIDTH = 176;
    private static final int MARGIN = 12;
    private static final int CHARGE_Y = BatteryMenu.SLOT_Y + 19;
    private static final int BAR_Y = CHARGE_Y + 11;
    private static final int BAR_HEIGHT = 14;
    private static final int FLOW_Y = BAR_Y + BAR_HEIGHT + 8;
    private static final int BLOCKS_Y = FLOW_Y + 14;
    private static final int HEIGHT = BatteryMenu.INVENTORY_Y + 58 + 18 + 6;
    /** Least space left between the power going in and the power going out. */
    private static final int GAP = 6;

    /** A written reading that was abbreviated, where it sits on the panel, and what it says in full. */
    private record Spot(int x, int y, int width, int height, Component exact) {}

    private final List<Spot> spots = new ArrayList<>();

    public BatteryScreen(BatteryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = BatteryMenu.INVENTORY_Y - 10;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        spots.clear();
        long stored = menu.stored();
        long capacity = menu.capacity();
        // A big battery's numbers may not fit across the bar written out in full.
        ReadingFit.Shortened bar = ReadingFit.fit(stored, capacity, WIDTH - 2 * MARGIN - 6,
                (x, y) -> font.width(readout("screen.jasm.battery.charge", stored, x, capacity, y)));
        Component charge = readout("screen.jasm.battery.charge", stored, bar.first(), capacity, bar.second());
        if (bar.any()) {
            spots.add(new Spot(MARGIN, BAR_Y, WIDTH - 2 * MARGIN, BAR_HEIGHT,
                    readout("screen.jasm.battery.charge", stored, false, capacity, false)));
        }
        JasmGui.labelledBar(graphics, font, charge, leftPos + MARGIN, topPos + BAR_Y, WIDTH - 2 * MARGIN, BAR_HEIGHT,
                capacity <= 0 ? 0 : stored / (double) capacity);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        long inFlow = menu.in();
        long outFlow = menu.out();
        ReadingFit.Shortened flow = ReadingFit.fit(inFlow, outFlow, WIDTH - 2 * MARGIN - GAP,
                (a, b) -> font.width(flow("screen.jasm.battery.in", inFlow, a)) + font.width(flow("screen.jasm.battery.out", outFlow, b)));
        Component in = flow("screen.jasm.battery.in", inFlow, flow.first());
        Component out = flow("screen.jasm.battery.out", outFlow, flow.second());
        int outX = WIDTH - MARGIN - font.width(out);
        graphics.text(font, in, MARGIN, FLOW_Y, inFlow > 0 ? JasmGui.GOOD : JasmGui.SUBTEXT, false);
        graphics.text(font, out, outX, FLOW_Y, outFlow > 0 ? JasmGui.WARN : JasmGui.SUBTEXT, false);
        if (flow.first()) spots.add(new Spot(MARGIN, FLOW_Y, font.width(in), 9, flow("screen.jasm.battery.in", inFlow, false)));
        if (flow.second()) spots.add(new Spot(outX, FLOW_Y, font.width(out), 9, flow("screen.jasm.battery.out", outFlow, false)));
        Component blocks = Component.translatable("screen.jasm.battery.blocks", String.format("%,d", menu.blocks()),
                String.format("%,d", menu.maxBlocks()));
        graphics.text(font, blocks, (WIDTH - font.width(blocks)) / 2, BLOCKS_Y, JasmGui.MUTED, false);
        EnergyHandler item = CreativeBatteryMenu.batteryOf(menu.charging());
        // The item's charge, centred under the slot, only while something is in it.
        if (item != null) {
            long amount = item.getAmountAsLong();
            long room = item.getCapacityAsLong();
            ReadingFit.Shortened shown = ReadingFit.fit(amount, room, WIDTH - 2 * MARGIN,
                    (a, b) -> font.width(readout("screen.jasm.battery.slot_charge", amount, a, room, b)));
            Component charge = readout("screen.jasm.battery.slot_charge", amount, shown.first(), room, shown.second());
            if (shown.any()) {
                spots.add(new Spot((WIDTH - font.width(charge)) / 2, CHARGE_Y, font.width(charge), 9,
                        readout("screen.jasm.battery.slot_charge", amount, false, room, false)));
            }
            graphics.text(font, charge, (WIDTH - font.width(charge)) / 2, CHARGE_Y, JasmGui.SUBTEXT, false);
        }
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        int x = mouseX - leftPos;
        int y = mouseY - topPos;
        // The exact figure of an abbreviated reading, while the mouse is on it.
        for (Spot spot : spots) {
            if (x >= spot.x() && x < spot.x() + spot.width() && y >= spot.y() && y < spot.y() + spot.height()) {
                graphics.setTooltipForNextFrame(font, spot.exact(), mouseX, mouseY);
                return;
            }
        }
    }

    private static String number(long value, boolean abbreviated) {
        return abbreviated ? GridEntries.abbreviate(value) : String.format("%,d", value);
    }

    private static Component readout(String key, long first, boolean firstShort, long second, boolean secondShort) {
        return Component.translatable(key, number(first, firstShort), number(second, secondShort));
    }

    private static Component flow(String key, long value, boolean abbreviated) {
        return Component.translatable(key, number(value, abbreviated));
    }
}
