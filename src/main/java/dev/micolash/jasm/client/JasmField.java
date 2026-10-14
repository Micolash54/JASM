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
    private boolean selectAllOnClick;
    // set by a click that selected everything, so the mouse moving before it is let go doesn't undo that
    private boolean keepSelection;

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

    /** For search fields: a click into the field while not typing in it selects all its text, so typing replaces it. */
    public void setSelectAllOnClick() {
        selectAllOnClick = true;
    }

    @Override
    public int getInnerWidth() {
        return (int) ((getWidth() - 2 * PAD) / scale);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // The screen hands focus to the field only after this click, so a field not yet focused is being clicked into.
        keepSelection = selectAllOnClick && !isFocused() && !getValue().isEmpty();
        if (keepSelection) {
            moveCursorToEnd(false);
            setHighlightPos(0);
            return;
        }
        super.onClick(new MouseButtonEvent(getX() + (event.x() - getX() - PAD) / scale, event.y(), event.buttonInfo()), doubleClick);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (!keepSelection) super.onDrag(event, dx, dy);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        keepSelection = false;
        super.onRelease(event);
    }

    @Override
    public void setFocused(boolean focused) {
        if (!focused) keepSelection = false;
        super.setFocused(focused);
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
