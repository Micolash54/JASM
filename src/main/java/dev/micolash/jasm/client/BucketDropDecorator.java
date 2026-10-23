package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckBucket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** A Crafting Deck with a fluid picked for its bucket shows a drop of that fluid on its bottom-left corner. */
final class BucketDropDecorator implements IItemDecorator {
    private static final Identifier FRAME = Jasm.id("icon/fluid_drop_frame");
    private static final int WIDTH = 7;
    private static final int HEIGHT = 8;
    /** The inside of the drop, row by row, as x, y, width, height from its top-left corner. */
    private static final int[][] INSIDE = {{3, 1, 1, 1}, {2, 2, 3, 1}, {1, 3, 5, 3}, {2, 6, 3, 1}};

    @Override
    public boolean render(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
        FluidResource fluid = DeckBucket.picked(stack);
        if (fluid.isEmpty()) {
            return false;
        }
        int dx = x - 1;
        int dy = y + 16 - HEIGHT;
        graphics.nextStratum();
        // The fluid's own texture, cut to the drop's shape, then the outline and shine over it.
        for (int[] part : INSIDE) {
            graphics.enableScissor(dx + part[0], dy + part[1], dx + part[0] + part[2], dy + part[1] + part[3]);
            FluidGrid.draw(graphics, fluid, dx, dy);
            graphics.disableScissor();
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FRAME, dx, dy, WIDTH, HEIGHT);
        return true;
    }
}
