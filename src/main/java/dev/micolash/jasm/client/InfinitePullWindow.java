package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.wafer.FluidAmounts;
import dev.micolash.jasm.wafer.InfiniteWaferItem;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * An infinite wafer's small window, opened by right-clicking it in the Deck's wafer panel: one Pull all key that deletes
 * its fluid from the Deck's other wafers, and the result underneath. Moved by dragging any empty spot of it.
 */
final class InfinitePullWindow {
    static final int WIDTH = 150;
    static final int HEIGHT = 64;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final DeckMenu menu;
    private final Font font;
    private final JasmButton pull;
    private final JasmButton close;
    private int slot = -1;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    InfinitePullWindow(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        pull = JasmButton.text(Component.translatable("screen.jasm.infinite.pull"),
                b -> ClientPacketDistributor.sendToServer(new DeckPayloads.PullInfinite(menu.containerId, slot)), 0, 0, 56, 17);
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
    }

    boolean isOpen() {
        return slot >= 0;
    }

    int selected() {
        return slot;
    }

    /** Opens for the infinite wafer in {@code slot}, over the middle of the Deck, kept on screen. */
    void open(int slot, int left, int top, int screenWidth, int screenHeight) {
        this.slot = slot;
        menu.view().setPulled(null);
        x = Math.clamp(left, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp(top, 0, Math.max(0, screenHeight - HEIGHT - 11));
        ItemStack wafer = wafer();
        if (wafer.getItem() instanceof InfiniteWaferItem infinite) {
            pull.setTooltip(Tooltip.create(Component.translatable("screen.jasm.infinite.pull_tip", infinite.resource().getHoverName())));
        }
    }

    void close() {
        slot = -1;
        grabX = -1;
        menu.view().setPulled(null);
    }

    private ItemStack wafer() {
        return slot >= 0 && slot < menu.waferSlots() ? menu.slots.get(slot).getItem() : ItemStack.EMPTY;
    }

    /** Whether the slot still holds an infinite wafer; the screen shuts the window when it doesn't. */
    boolean stillThere() {
        return wafer().getItem() instanceof InfiniteWaferItem && !menu.isOverflow(slot);
    }

    boolean contains(double mouseX, double mouseY) {
        return isOpen() && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    Optional<Rect2i> area() {
        return isOpen() ? Optional.of(new Rect2i(x, y, WIDTH + 3, height() + 3)) : Optional.empty();
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!isOpen()) return;
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, height());
        graphics.text(font, font.substrByWidth(wafer().getHoverName(), WIDTH - 30).getString(), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        close.setPosition(x + WIDTH - 16, y + 5);
        pull.setPosition(x + 8, y + 26);
        pull.active = menu.dimensionAllowed() && stillThere();
        close.extractRenderState(graphics, mouseX, mouseY, a);
        pull.extractRenderState(graphics, mouseX, mouseY, a);
        var done = menu.view().pulled();
        Component line;
        int colour;
        if (done == null || done.slot() != slot) {
            line = Component.translatable("screen.jasm.infinite.pull_hint");
            colour = JasmGui.SUBTEXT;
        } else if (done.amount() > 0) {
            line = Component.translatable("screen.jasm.infinite.pulled", FluidAmounts.buckets(done.amount()));
            colour = done.outOfCharge() ? JasmGui.WARN : JasmGui.GOOD;
        } else if (done.outOfCharge()) {
            line = Component.translatable("screen.jasm.infinite.out_of_charge");
            colour = JasmGui.WARN;
        } else {
            line = Component.translatable("screen.jasm.infinite.nothing");
            colour = JasmGui.SUBTEXT;
        }
        graphics.text(font, font.substrByWidth(line, WIDTH - 16).getString(), x + 8, y + 49, colour, false);
        if (shortOfCharge()) {
            graphics.text(font, Component.translatable("screen.jasm.infinite.out_of_charge"), x + 8, y + 60, JasmGui.WARN, false);
        }
    }

    /** Whether the last pull stopped part way for lack of charge; the window grows a line to say so. */
    private boolean shortOfCharge() {
        var done = menu.view().pulled();
        return done != null && done.slot() == slot && done.outOfCharge() && done.amount() > 0;
    }

    private int height() {
        return shortOfCharge() ? HEIGHT + 11 : HEIGHT;
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || pull.mouseClicked(event, doubleClick)) return true;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key picks it up. */
    private boolean grabbable(double mx, double my) {
        return contains(mx, my) && !close.isMouseOver(mx, my) && !pull.isMouseOver(mx, my);
    }

    /** Moves the window with the mouse while it is held, kept on screen. */
    boolean mouseDragged(MouseButtonEvent event, int screenWidth, int screenHeight) {
        if (grabX < 0) return false;
        x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, screenHeight - height()));
        return true;
    }

    boolean mouseReleased() {
        boolean held = grabX >= 0;
        grabX = -1;
        return held;
    }

    /** Escape shuts the window. */
    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            close();
            return true;
        }
        return false;
    }
}
