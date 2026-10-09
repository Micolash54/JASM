package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A text field in the JASM look: a dark well that brightens under the mouse and gets a lavender rim while typing. */
public class JasmField extends EditBox {
    private static final Identifier NORMAL = Jasm.id("field");
    private static final Identifier HIGHLIGHTED = Jasm.id("field_highlighted");
    private static final Identifier FOCUSED = Jasm.id("field_focused");
    private static final int PAD = 4;
    private float scale = 1F;

    public JasmField(Font font, int x, int y, int width, int height, Component narration) {
        super(font, x, y, width, height, narration);
        // The game's own frame is replaced by the well below; the text is moved in by hand.
        setBordered(false);
        setTextColor(JasmGui.TEXT);
        setTextColorUneditable(JasmGui.MUTED);
        setTextShadow(false);
    }

    /** Hints are always the muted grey, whatever colour they were given. */
    @Override
    public void setHint(Component hint) {
        super.setHint(hint.copy().withStyle(style -> style.withColor(JasmGui.MUTED & 0xFFFFFF)));
    }

    /** Draws the text at {@link JasmGui#SMALL_TEXT} size, for a small field on a crowded line. */
    public void setSmallText() {
        scale = JasmGui.SMALL_TEXT;
    }

    @Override
    public int getInnerWidth() {
        return (int) ((getWidth() - 2 * PAD) / scale);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        super.onClick(new MouseButtonEvent(getX() + (event.x() - getX() - PAD) / scale, event.y(), event.buttonInfo()), doubleClick);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!isVisible()) return;
        Identifier sprite = isFocused() ? FOCUSED : isHovered() && isActive() ? HIGHLIGHTED : NORMAL;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
        graphics.pose().pushMatrix();
        graphics.pose().translate(getX() + PAD, getY() + (int) ((getHeight() - 8 * scale) / 2));
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-getX(), -getY());
        super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
        graphics.pose().popMatrix();
    }
}
