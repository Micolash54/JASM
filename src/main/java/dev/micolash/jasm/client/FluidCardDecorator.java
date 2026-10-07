package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.Card;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.MaterialMarkerItem;
import dev.micolash.jasm.core.MaterialKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** Draws the fluid a card makes in the card's slot while Shift is held, as an item model cannot show a fluid. */
final class FluidCardDecorator implements IItemDecorator {
    @Override
    public boolean render(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
        if (!Minecraft.getInstance().hasShiftDown()) {
            return false;
        }
        Card card = Card.of(stack);
        FluidResource fluid = card == null ? null : FluidMarkerItem.fluidOf(card.result());
        if (fluid != null) {
            FluidGrid.draw(graphics, fluid, x, y);
            return true;
        }
        MaterialKey material = card == null ? null : MaterialMarkerItem.materialOf(card.result());
        if (material == null) {
            return false;
        }
        MaterialIcons.draw(graphics, material, x, y);
        return true;
    }
}
