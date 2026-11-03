package dev.micolash.jasm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.util.Mth;

/** A scrolling list in the JASM look: a dark well with a rounded track and a key for a handle, and no game-menu stripes. */
abstract class SettingsPane<E extends ContainerObjectSelectionList.Entry<E>> extends ContainerObjectSelectionList<E> {
    static final int PAD = 4;

    SettingsPane(Minecraft minecraft, int x, int y, int width, int height, int entryHeight) {
        super(minecraft, width, height, y, entryHeight);
        setX(x);
    }

    @Override
    public int getRowLeft() {
        return getX() + PAD;
    }

    @Override
    public int getRowWidth() {
        return getWidth() - 2 * PAD - scrollbarWidth() - 2;
    }

    @Override
    protected int scrollBarX() {
        return getX() + getWidth() - scrollbarWidth() - 2;
    }

    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
        JasmGui.inset(graphics, getX(), getY(), getWidth(), getHeight());
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor graphics) {}

    /** The handle shrinks as the list grows, but never below a key's size. */
    @Override
    protected int scrollerHeight() {
        return Mth.clamp((int) ((long) height * height / Math.max(1, contentHeight())), 20, Math.max(20, height - 8));
    }

    @Override
    protected void extractScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int track = getHeight() - 4;
        int handle = scrollerHeight() - 4;
        double along = maxScrollAmount() == 0 ? 0 : scrollAmount() / maxScrollAmount();
        JasmGui.scrollBar(graphics, scrollBarX(), getY() + 2, scrollbarWidth(), track, (int) Math.round(along * (track - handle - 2)), handle,
                scrollable());
    }

    @Override
    protected void enableScissor(GuiGraphicsExtractor graphics) {
        graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
    }
}
