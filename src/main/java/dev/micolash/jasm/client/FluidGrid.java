package dev.micolash.jasm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.item.ItemStack;
import dev.micolash.jasm.deck.DeckFluids;
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

    /** The first fluid the stack holds (a bucket of water, a filled tank), or null if it holds none or isn't a container. */
    static @Nullable FluidResource containedFluid(ItemStack stack) {
        return DeckFluids.contained(stack);
    }
}
