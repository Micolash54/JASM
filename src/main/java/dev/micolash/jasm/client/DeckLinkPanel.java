package dev.micolash.jasm.client;

import dev.micolash.jasm.network.DeckLinkLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Drawing and hit area for the Deck linking controls. */
final class DeckLinkPanel {
    private static final Identifier ARROW = dev.micolash.jasm.Jasm.id("icon/craft_arrow");

    private DeckLinkPanel() {}

    static boolean contains(double mouseX, double mouseY, int left, int top, DeckLinkLayout layout) {
        return mouseX >= left + layout.panelX() && mouseX < left - 2
                && mouseY >= top && mouseY < top + layout.height();
    }

    static void draw(GuiGraphicsExtractor graphics, Font font, int left, int top, DeckLinkLayout layout) {
        int x = left + layout.panelX();
        JasmGui.panel(graphics, x, top, layout.width(), layout.height());
        Component title = Component.translatable("screen.jasm.deck_link");
        graphics.text(font, title, x + (layout.width() - font.width(title)) / 2, top + 8, JasmGui.TEXT, false);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, left + layout.slotX() + 3,
                top + DeckLinkLayout.INPUT_Y + 22, 9, 9);
    }

    static void message(GuiGraphicsExtractor graphics, Font font, Component text, int color, DeckLinkLayout layout) {
        var lines = font.split(text, layout.width() - 16);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            graphics.text(font, lines.get(i), layout.panelX() + 8, DeckLinkLayout.MESSAGE_Y + 9 * i, color, false);
        }
    }
}
