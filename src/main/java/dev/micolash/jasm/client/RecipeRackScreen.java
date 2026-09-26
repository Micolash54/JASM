package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.RecipeRackMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** The Recipe Rack screen: two rows of card slots under the title and charge bar. */
public class RecipeRackScreen extends AbstractContainerScreen<RecipeRackMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = RecipeRackMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;

    public RecipeRackScreen(RecipeRackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = RecipeRackMenu.INVENTORY_Y - 10;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
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
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        if (!menu.running()) {
            // Between the cards and the inventory, wrapped to the panel.
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("screen.jasm.rack.no_power"), imageWidth - 16);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.text(font, lines.get(i), (imageWidth - font.width(lines.get(i))) / 2, RecipeRackMenu.CARDS_Y + 39 + i * 9, JasmGui.BAD, false);
            }
        }
    }
}
