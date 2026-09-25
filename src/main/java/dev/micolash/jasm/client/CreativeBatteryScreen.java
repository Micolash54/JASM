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

    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int TEXT = 0xFF404040;
    private static final int CHARGE = 0xFF3FA34D;

    public CreativeBatteryScreen(CreativeBatteryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = CreativeBatteryMenu.INVENTORY_Y - 10;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, PANEL_DARK);
        for (Slot slot : menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, SLOT);
        }
        int bx = x + (imageWidth - BAR_WIDTH) / 2;
        graphics.fill(bx - 1, y + BAR_Y, bx + BAR_WIDTH + 1, y + BAR_Y + 7, PANEL_DARK);
        EnergyHandler battery = CreativeBatteryMenu.batteryOf(menu.charging());
        if (battery != null && battery.getCapacityAsLong() > 0) {
            int filled = (int) Math.round(BAR_WIDTH * Math.min(1.0, battery.getAmountAsLong() / (double) battery.getCapacityAsLong()));
            graphics.fill(bx, y + BAR_Y + 1, bx + filled, y + BAR_Y + 6, CHARGE);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        EnergyHandler battery = CreativeBatteryMenu.batteryOf(menu.charging());
        Component status = battery == null
                ? Component.translatable("screen.jasm.creative_battery.empty")
                : Component.translatable("screen.jasm.creative_battery.charge",
                        String.format("%,d", battery.getAmountAsLong()), String.format("%,d", battery.getCapacityAsLong()));
        graphics.text(font, status, (imageWidth - font.width(status)) / 2, BAR_Y + 10, TEXT, false);
    }
}
