package dev.micolash.jasm.client;

import dev.micolash.jasm.battery.BatteryMenu;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.core.GridEntries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/**
 * The Battery screen: the charge of the whole battery across a bar, the power going in and out under it, and how many
 * blocks share it. Every block of a battery shows the same. Above all that, the battery's charging slot with the item's
 * charge under it, and below, the player's inventory.
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

    public BatteryScreen(BatteryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = BatteryMenu.INVENTORY_Y - 10;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        Component charge = Component.translatable("screen.jasm.battery.charge", String.format("%,d", menu.stored()),
                String.format("%,d", menu.capacity()));
        // A big battery's numbers don't fit across the bar written out in full.
        if (font.width(charge) > WIDTH - 2 * MARGIN - 6) {
            charge = Component.translatable("screen.jasm.battery.charge", GridEntries.abbreviate(menu.stored()),
                    GridEntries.abbreviate(menu.capacity()));
        }
        JasmGui.labelledBar(graphics, font, charge, leftPos + MARGIN, topPos + BAR_Y, WIDTH - 2 * MARGIN, BAR_HEIGHT,
                menu.capacity() <= 0 ? 0 : menu.stored() / (double) menu.capacity());
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        Component in = Component.translatable("screen.jasm.battery.in", String.format("%,d", menu.in()));
        Component out = Component.translatable("screen.jasm.battery.out", String.format("%,d", menu.out()));
        graphics.text(font, in, MARGIN, FLOW_Y, menu.in() > 0 ? JasmGui.GOOD : JasmGui.SUBTEXT, false);
        graphics.text(font, out, WIDTH - MARGIN - font.width(out), FLOW_Y, menu.out() > 0 ? JasmGui.WARN : JasmGui.SUBTEXT, false);
        Component blocks = Component.translatable("screen.jasm.battery.blocks", String.format("%,d", menu.blocks()),
                String.format("%,d", menu.maxBlocks()));
        graphics.text(font, blocks, (WIDTH - font.width(blocks)) / 2, BLOCKS_Y, JasmGui.MUTED, false);
        EnergyHandler item = CreativeBatteryMenu.batteryOf(menu.charging());
        // The item's charge, centred under the slot, only while something is in it.
        if (item != null) {
            Component charge = Component.translatable("screen.jasm.battery.slot_charge", String.format("%,d", item.getAmountAsLong()),
                    String.format("%,d", item.getCapacityAsLong()));
            if (font.width(charge) > WIDTH - 2 * MARGIN) {
                charge = Component.translatable("screen.jasm.battery.slot_charge", GridEntries.abbreviate(item.getAmountAsLong()),
                        GridEntries.abbreviate(item.getCapacityAsLong()));
            }
            graphics.text(font, charge, (WIDTH - font.width(charge)) / 2, CHARGE_Y, JasmGui.SUBTEXT, false);
        }
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
    }
}
