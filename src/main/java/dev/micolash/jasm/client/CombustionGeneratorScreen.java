package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** The generator screen: fuel slot and flame, the charge bar, then the charging slot, with the status above and the charge below. */
public class CombustionGeneratorScreen extends AbstractContainerScreen<CombustionGeneratorMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = CombustionGeneratorMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final Identifier FLAME = Jasm.id("icon/flame");
    private static final Identifier FLAME_EMPTY = Jasm.id("icon/flame_empty");
    private static final int FLAME_SIZE = 14;
    private static final int FLAME_X = CombustionGeneratorMenu.FUEL_X + 20;
    private static final int FLAME_Y = CombustionGeneratorMenu.SLOT_Y + 1;
    private static final int BAR_X = FLAME_X + FLAME_SIZE + 6;
    private static final int BAR_WIDTH = CombustionGeneratorMenu.CHARGE_X - 8 - BAR_X;
    private static final int BAR_Y = CombustionGeneratorMenu.SLOT_Y + 4;
    private static final int STATUS_Y = 24;
    private static final int CHARGE_TEXT_Y = CombustionGeneratorMenu.SLOT_Y + 20;

    public CombustionGeneratorScreen(CombustionGeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = CombustionGeneratorMenu.INVENTORY_Y - 10;
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
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FLAME_EMPTY, x + FLAME_X, y + FLAME_Y, FLAME_SIZE, FLAME_SIZE);
        float flame = menu.flame();
        if (flame > 0) {
            // The flame shrinks from the top as the fuel item burns down, like a furnace's.
            int height = Mth.ceil(flame * (FLAME_SIZE - 1)) + 1;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FLAME, FLAME_SIZE, FLAME_SIZE, 0, FLAME_SIZE - height,
                    x + FLAME_X, y + FLAME_Y + FLAME_SIZE - height, FLAME_SIZE, height);
        }
        JasmGui.bar(graphics, x + BAR_X - 1, y + BAR_Y, BAR_WIDTH + 2, 7, menu.energy() / (double) CombustionGeneratorBlockEntity.CAPACITY);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component status;
        int color;
        if (menu.output() > 0) {
            status = Component.translatable("screen.jasm.combustion_generator.burning", String.format("%,d", menu.output()));
            color = JasmGui.GOOD;
        } else if (menu.flame() > 0 || menu.energy() >= CombustionGeneratorBlockEntity.CAPACITY) {
            status = Component.translatable("screen.jasm.combustion_generator.full");
            color = JasmGui.SUBTEXT;
        } else {
            status = Component.translatable("screen.jasm.combustion_generator.no_fuel");
            color = JasmGui.MUTED;
        }
        graphics.text(font, status, (imageWidth - font.width(status)) / 2, STATUS_Y, color, false);
        Component charge = Component.translatable("screen.jasm.combustion_generator.charge",
                String.format("%,d", menu.energy()), String.format("%,d", CombustionGeneratorBlockEntity.CAPACITY));
        graphics.text(font, charge, (imageWidth - font.width(charge)) / 2, CHARGE_TEXT_Y, JasmGui.SUBTEXT, false);
    }
}
