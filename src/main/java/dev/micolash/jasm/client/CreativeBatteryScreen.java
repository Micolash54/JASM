package dev.micolash.jasm.client;

import dev.micolash.jasm.battery.CreativeBatteryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/** The Creative Battery screen: the charging slot with a charge bar under it, and the player's inventory. */
public class CreativeBatteryScreen extends AbstractContainerScreen<CreativeBatteryMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = CreativeBatteryMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 80;
    private static final int BAR_Y = CreativeBatteryMenu.SLOT_Y + 22;

    public CreativeBatteryScreen(CreativeBatteryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = CreativeBatteryMenu.INVENTORY_Y - 10;
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
        int bx = x + (imageWidth - BAR_WIDTH) / 2;
        EnergyHandler battery = CreativeBatteryMenu.batteryOf(menu.charging());
        double fraction = battery == null || battery.getCapacityAsLong() <= 0 ? 0 : battery.getAmountAsLong() / (double) battery.getCapacityAsLong();
        JasmGui.bar(graphics, bx - 1, y + BAR_Y, BAR_WIDTH + 2, 7, fraction);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        EnergyHandler battery = CreativeBatteryMenu.batteryOf(menu.charging());
        Component status = battery == null
                ? Component.translatable("screen.jasm.creative_battery.empty")
                : Component.translatable("screen.jasm.creative_battery.charge",
                        String.format("%,d", battery.getAmountAsLong()), String.format("%,d", battery.getCapacityAsLong()));
        graphics.text(font, status, (imageWidth - font.width(status)) / 2, BAR_Y + 10, JasmGui.SUBTEXT, false);
    }
}
