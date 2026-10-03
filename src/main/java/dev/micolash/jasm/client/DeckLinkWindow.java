package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.LinkWindowCover;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Deck Link window shared by the Archive, the Encoding Terminal and the ports: a Deck goes in the left slot and
 * comes out linked on the right. Below them, centred, a line or two about the link and, where the machine has one, a
 * key that gives the link back to the owner. Opened from a side key, drawn above the screen and moved by dragging any
 * empty spot of it. Its two slots are the menu's own and follow the window around.
 */
final class DeckLinkWindow {
    static final int WIDTH = 150;
    private static final int SLOT_Y = 30;
    private static final int IN_X = WIDTH / 2 - 30;
    private static final int OUT_X = IN_X + 44;
    private static final int TEXT_Y = 56;
    private static final int LINE = 10;
    private static final Identifier ARROW = Jasm.id("icon/craft_arrow_wide");
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final Font font;
    private final Slot input;
    private final Slot output;
    private final LinkWindowCover cover;
    private final JasmButton close;
    private final @Nullable JasmButton reset;
    private boolean open;
    private int x;
    private int y;
    private int lines = 1;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    /** {@code resetLabel} is null for machines without a reset key. */
    DeckLinkWindow(Font font, Slot input, Slot output, LinkWindowCover cover, @Nullable Component resetLabel, Runnable onReset) {
        this.font = font;
        this.input = input;
        this.output = output;
        this.cover = cover;
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
        reset = resetLabel == null ? null : JasmButton.text(resetLabel, b -> onReset.run(), 0, 0, font.width(resetLabel) + 16, 16);
    }

    /** "Linked:" and who to, lit up green when {@code lit}; or how to link a Deck when nobody is known. */
    static Component linkedTo(String player, boolean lit) {
        if (player.isEmpty()) return Component.translatable("screen.jasm.archive.no_deck");
        return Component.translatable("screen.jasm.archive.linked_player").withColor((lit ? JasmGui.GOOD : JasmGui.SUBTEXT) & 0xFFFFFF)
                .append(" ").append(Component.literal(player).withColor(JasmGui.MUTED & 0xFFFFFF));
    }

    boolean isOpen() {
        return open;
    }

    /** Opens the window just left of the side key at {@code keyX}, a little below the screen's top; or shuts it. */
    void toggle(int keyX, int top) {
        if (open) {
            close();
        } else {
            open = true;
            x = keyX - 6 - WIDTH;
            y = top + 40;
        }
    }

    void close() {
        open = false;
        grabX = -1;
        cover.set(null);
    }

    void setResetActive(boolean active) {
        if (reset != null) reset.active = active;
    }

    private int resetY() {
        return TEXT_Y + lines * LINE + 3;
    }

    int height() {
        return reset != null ? resetY() + 16 + 8 : TEXT_Y + lines * LINE + 6;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    Optional<Rect2i> area() {
        return open ? Optional.of(new Rect2i(x, y, WIDTH + 3, height() + 3)) : Optional.empty();
    }

    /** Moves the two slots to where the window is and tells the menu what it covers. Call before the screen draws. */
    void sync(int left, int top) {
        if (!open) {
            cover.set(null);
            return;
        }
        input.x = x - left + IN_X;
        input.y = y - top + SLOT_Y;
        output.x = x - left + OUT_X;
        output.y = y - top + SLOT_Y;
        cover.set(new int[]{input.x, input.y, output.x, output.y});
    }

    boolean overSlot(double mouseX, double mouseY) {
        return open && mouseY >= y + SLOT_Y && mouseY < y + SLOT_Y + 16
                && (mouseX >= x + IN_X && mouseX < x + IN_X + 16 || mouseX >= x + OUT_X && mouseX < x + OUT_X + 16);
    }

    /** Whether the screen should treat the mouse as hidden: over the window, but not over one of its slots. */
    boolean hidesMouse(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) && !overSlot(mouseX, mouseY);
    }

    /** {@code status} is what to say about the link, centred under the slots; at most two lines show. */
    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, Component status) {
        if (!open) return;
        List<FormattedCharSequence> text = new ArrayList<>(font.split(status, WIDTH - 16));
        if (text.size() > 2) text = text.subList(0, 2);
        lines = Math.max(1, text.size());
        close.setPosition(x + WIDTH - 16, y + 5);
        if (reset != null) reset.setPosition(x + (WIDTH - reset.getWidth()) / 2, y + resetY());
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, height());
        graphics.text(font, Component.translatable("screen.jasm.deck_link"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        drawSlot(graphics, input, x + IN_X, mouseX, mouseY);
        drawSlot(graphics, output, x + OUT_X, mouseX, mouseY);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, x + IN_X + 26, y + SLOT_Y + 3, 9, 10);
        for (int i = 0; i < text.size(); i++) {
            graphics.text(font, text.get(i), x + (WIDTH - font.width(text.get(i))) / 2, y + TEXT_Y + i * LINE, JasmGui.SUBTEXT, false);
        }
        close.extractRenderState(graphics, mouseX, mouseY, a);
        if (reset != null) reset.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /** The slot well, then the item again above the window, since the screen draws slots underneath it. */
    private void drawSlot(GuiGraphicsExtractor graphics, Slot slot, int sx, int mouseX, int mouseY) {
        int sy = y + SLOT_Y;
        JasmGui.slot(graphics, sx, sy);
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            graphics.item(stack, sx, sy);
            graphics.itemDecorations(font, stack, sx, sy);
        } else if (slot.getNoItemIcon() != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, slot.getNoItemIcon(), sx, sy, 16, 16);
        }
        if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
            graphics.fill(sx, sy, sx + 16, sy + 16, JasmGui.HOVER);
        }
    }

    /** True when the click was the window's own; clicks on its slots are left to the screen. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || reset != null && reset.mouseClicked(event, doubleClick)) return true;
        if (overSlot(event.x(), event.y())) return false;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key or a slot picks it up. */
    private boolean grabbable(double mx, double my) {
        return contains(mx, my) && !overSlot(mx, my) && !close.isMouseOver(mx, my) && (reset == null || !reset.isMouseOver(mx, my));
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
}
