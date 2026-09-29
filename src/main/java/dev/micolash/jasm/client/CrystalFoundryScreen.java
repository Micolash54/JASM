package dev.micolash.jasm.client;

import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
import dev.micolash.jasm.crystal.CrystalFoundryMenu;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Foundry screen: the seed slot, the crystal it is growing with the progress under it and how many crystals the
 * seed has made so far, the output grid, and the power across the full width.
 */
public class CrystalFoundryScreen extends AbstractContainerScreen<CrystalFoundryMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = CrystalFoundryMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int CRYSTAL_X = 68;
    private static final int CRYSTAL_Y = 24;
    private static final int PROGRESS_X = 58;
    private static final int PROGRESS_WIDTH = 36;
    private static final int PROGRESS_Y = 47;
    private static final int COUNT_Y = 55;
    private static final int POWER_X = 8;
    private static final int POWER_WIDTH = WIDTH - 16;
    private static final int ROW_HEIGHT = 12;
    private final ItemStack crystal = new ItemStack(JasmItems.DATA_CRYSTAL.get());

    public CrystalFoundryScreen(CrystalFoundryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = CrystalFoundryMenu.INVENTORY_Y - 10;
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
        JasmGui.inset(graphics, x + CRYSTAL_X - 3, y + CRYSTAL_Y - 3, 22, 22);
        if (menu.growing()) {
            graphics.item(crystal, x + CRYSTAL_X, y + CRYSTAL_Y);
        }
        JasmGui.bar(graphics, x + PROGRESS_X, y + PROGRESS_Y, PROGRESS_WIDTH, 5, menu.growing() ? menu.progress() : 0);
        JasmGui.bar(graphics, x + POWER_X, y + CrystalFoundryMenu.ROW_Y, POWER_WIDTH, ROW_HEIGHT, menu.energy() / (double) CrystalFoundryBlockEntity.CAPACITY);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component count = menu.growing()
                ? Component.translatable("screen.jasm.foundry.seed", menu.made(), menu.perSeed())
                : Component.translatable("screen.jasm.foundry.no_seed");
        int color = menu.full() ? JasmGui.WARN : !menu.powered() && menu.growing() ? JasmGui.BAD : JasmGui.SUBTEXT;
        if (menu.full()) {
            count = Component.translatable("screen.jasm.foundry.full");
        } else if (!menu.powered() && menu.growing()) {
            count = Component.translatable("screen.jasm.foundry.no_power");
        }
        graphics.text(font, count, PROGRESS_X + (PROGRESS_WIDTH - font.width(count)) / 2, COUNT_Y, color, false);
        Component power = Component.translatable("screen.jasm.workshop.power_amount", String.format("%,d", menu.energy()),
                String.format("%,d", CrystalFoundryBlockEntity.CAPACITY));
        graphics.text(font, power, POWER_X + (POWER_WIDTH - font.width(power)) / 2, CrystalFoundryMenu.ROW_Y + 2, JasmGui.TEXT, true);
    }
}
