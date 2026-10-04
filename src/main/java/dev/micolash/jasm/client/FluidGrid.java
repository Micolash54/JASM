package dev.micolash.jasm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.item.ItemStack;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckFluids;
import dev.micolash.jasm.wafer.FluidAmounts;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/** Drawing a fluid in a grid cell, and reading the fluid out of the container on the cursor. */
final class FluidGrid {
    private FluidGrid() {}

    /** The fluid's still texture, tinted the way it is in the world, filling a 16 × 16 cell. */
    static void draw(GuiGraphicsExtractor graphics, FluidResource fluid, int x, int y) {
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.getFluid().defaultFluidState());
        var tint = model.fluidTintSource();
        int color = tint == null ? 0xFFFFFFFF : tint.colorAsStack(fluid.toStack(1)) | 0xFF000000;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, model.stillMaterial().sprite(), x, y, 16, 16, color);
    }

    /** As {@link #draw}, {@code size} pixels across and centred in the 16 × 16 cell, so what lies behind shows round it. */
    static void draw(GuiGraphicsExtractor graphics, FluidResource fluid, int x, int y, int size) {
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.getFluid().defaultFluidState());
        var tint = model.fluidTintSource();
        int color = tint == null ? 0xFFFFFFFF : tint.colorAsStack(fluid.toStack(1)) | 0xFF000000;
        int inset = (16 - size) / 2;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, model.stillMaterial().sprite(), x + inset, y + inset, size, size, color);
    }

    /** Draws a stack, or the fluid a fluid marker stands for, in a 16 × 16 cell. */
    static void drawStack(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        FluidResource fluid = FluidMarkerItem.fluidOf(stack);
        if (fluid != null) {
            draw(graphics, fluid, x, y);
        } else {
            graphics.item(stack, x, y);
        }
    }

    /** "3 × Stone" for items, "1.5 B Water" for a fluid marker (the count in millibuckets). */
    static String describe(ItemStack stack, long count) {
        boolean fluid = FluidMarkerItem.isMarker(stack);
        return fluid ? FluidAmounts.label(count) + " " + stack.getHoverName().getString()
                : GridEntries.abbreviate(count) + " × " + stack.getHoverName().getString();
    }

    /** The first fluid the stack holds (a bucket of water, a filled tank), or null if it holds none or isn't a container. */
    static @Nullable FluidResource containedFluid(ItemStack stack) {
        return DeckFluids.contained(stack);
    }
}
