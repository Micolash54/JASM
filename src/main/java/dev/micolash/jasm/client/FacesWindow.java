package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.InputFace;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Advanced Encoder's Faces window: seven face keys (Default, Top, Bottom and the four compass faces), each
 * in its colour with its letter, and what the letters mean. Picking a key starts painting with that face; the screen
 * then paints the ingredient slots that are clicked. The same size and frame as the Deck Link window, dragged by any
 * empty spot. It first opens over the inventory and then where the player last left it, for as long as the game runs.
 */
final class FacesWindow {
    static final int WIDTH = 150;
    static final int HEIGHT = 82;
    /** Where it first opens, from the screen's corner: over the inventory, so the grid stays in view. */
    static final int HOME_X = 13;
    static final int HOME_Y = 124;
    static final int KEY_X = 9;
    static final int KEY_Y = 26;
    static final int KEY_SIZE = 17;
    static final int KEY_PITCH = 19;
    private static final Identifier KEY = Jasm.id("button");
    private static final Identifier KEY_LIT = Jasm.id("button_highlighted");
    private static final Identifier KEY_DOWN = Jasm.id("button_pressed");
    private static final int OUTLINE = 0xFF11111B;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    /** The legend: three faces a row, then Default on a line of its own. */
    private static final InputFace[][] LEGEND = {
            {InputFace.TOP, InputFace.BOTTOM, InputFace.NORTH},
            {InputFace.SOUTH, InputFace.EAST, InputFace.WEST}};

    /** Where the player last left the window, from the screen's corner; kept while the game runs. */
    private static int savedX = HOME_X;
    private static int savedY = HOME_Y;

    private final Font font;
    private final JasmButton close;
    private boolean open;
    private int left;
    private int top;
    /** The face being painted with, or null when not painting. */
    private @Nullable InputFace picked;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    FacesWindow(Font font) {
        this.font = font;
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        if (open) {
            close();
        } else {
            open = true;
        }
    }

    /** Shuts the window and stops painting. */
    void close() {
        open = false;
        picked = null;
        grabX = -1;
    }

    /** The face being painted with, or null. */
    @Nullable InputFace picked() {
        return picked;
    }

    void stopPainting() {
        picked = null;
    }

    /** Keeps the window on screen and follows the screen when it moves. Call before the screen draws. */
    void sync(int screenLeft, int screenTop, int screenWidth, int screenHeight) {
        left = screenLeft;
        top = screenTop;
        savedX = Math.clamp(savedX, -screenLeft, Math.max(-screenLeft, screenWidth - screenLeft - WIDTH));
        savedY = Math.clamp(savedY, -screenTop, Math.max(-screenTop, screenHeight - screenTop - HEIGHT));
    }

    private int x() {
        return left + savedX;
    }

    private int y() {
        return top + savedY;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x() && mouseX < x() + WIDTH && mouseY >= y() && mouseY < y() + HEIGHT;
    }

    Optional<Rect2i> area() {
        return open ? Optional.of(new Rect2i(x(), y(), WIDTH + 3, HEIGHT + 3)) : Optional.empty();
    }

    /** The face key under the mouse, or null. */
    private @Nullable InputFace keyAt(double mouseX, double mouseY) {
        if (!open || mouseY < y() + KEY_Y || mouseY >= y() + KEY_Y + KEY_SIZE) {
            return null;
        }
        for (InputFace face : InputFace.values()) {
            int kx = x() + KEY_X + face.ordinal() * KEY_PITCH;
            if (mouseX >= kx && mouseX < kx + KEY_SIZE) {
                return face;
            }
        }
        return null;
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) return;
        int x = x();
        int y = y();
        close.setPosition(x + WIDTH - 16, y + 5);
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, HEIGHT);
        graphics.text(font, Component.translatable("screen.jasm.terminal.faces"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        InputFace hovered = keyAt(mouseX, mouseY);
        for (InputFace face : InputFace.values()) {
            key(graphics, face, x + KEY_X + face.ordinal() * KEY_PITCH, y + KEY_Y, face == picked, face == hovered);
        }
        for (int row = 0; row < LEGEND.length; row++) {
            for (int column = 0; column < LEGEND[row].length; column++) {
                InputFace face = LEGEND[row][column];
                int tx = x + 9 + column * 48;
                int ty = y + 48 + row * 10;
                graphics.text(font, String.valueOf(face.letter()), tx, ty, face.color(), false);
                graphics.text(font, Component.translatable(face.translationKey()), tx + 8, ty, JasmGui.SUBTEXT, false);
            }
        }
        graphics.text(font, String.valueOf(InputFace.DEFAULT.letter()), x + 9, y + 68, JasmGui.TEXT, false);
        graphics.text(font, Component.translatable("screen.jasm.terminal.faces_default"), x + 17, y + 68, JasmGui.SUBTEXT, false);
        close.extractRenderState(graphics, mouseX, mouseY, a);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, Component.translatable(hovered.translationKey()), mouseX, mouseY);
        }
    }

    /**
     * One face key: the key with its face in the face's colour and the letter on it; Default keeps the plain key. The
     * picked one is pushed in with a lavender rim.
     */
    static void key(GuiGraphicsExtractor graphics, InputFace face, int x, int y, boolean picked, boolean hovered) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, picked ? KEY_DOWN : hovered ? KEY_LIT : KEY, x, y, KEY_SIZE, KEY_SIZE);
        int dy = picked ? 2 : 0;
        if (face != InputFace.DEFAULT) {
            graphics.fill(x + 2, y + 2 + dy, x + KEY_SIZE - 2, y + KEY_SIZE - 4 + dy, face.color());
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        String letter = String.valueOf(face.letter());
        graphics.text(font, letter, x + (KEY_SIZE - font.width(letter) + 1) / 2, y + (KEY_SIZE - 8) / 2 - 1 + dy,
                face == InputFace.DEFAULT ? JasmGui.TEXT : OUTLINE, false);
        if (picked) {
            JasmGui.rim(graphics, x - 1, y + 1, KEY_SIZE + 2, KEY_SIZE, JasmGui.ACCENT);
        }
    }

    /** The face in hand, following the mouse like a picked-up item: a small square in its colour with its letter. */
    static void held(GuiGraphicsExtractor graphics, Font font, InputFace face, int mouseX, int mouseY) {
        int x = mouseX - 11;
        int y = mouseY + 3;
        graphics.fill(x, y, x + 9, y + 9, OUTLINE);
        graphics.fill(x + 1, y + 1, x + 8, y + 8, face.color());
        Component letter = Component.literal(String.valueOf(face.letter()));
        JasmGui.smallText(graphics, font, letter, x + (9 - JasmGui.smallWidth(font, letter) + 1) / 2, y + 2,
                face == InputFace.DEFAULT ? JasmGui.TEXT : OUTLINE);
    }

    /** True when the click was the window's own. A face key starts painting with it, or stops when it is the one in hand. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick)) {
            return true;
        }
        InputFace face = keyAt(event.x(), event.y());
        if (face != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            picked = picked == face ? null : face;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x();
            grabY = (int) event.y() - y();
        }
        return true;
    }

    /** Any spot of the window that isn't a key picks it up. */
    private boolean grabbable(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) && keyAt(mouseX, mouseY) == null && !close.isMouseOver(mouseX, mouseY);
    }

    /** Moves the window with the mouse while it is held, kept on screen; where it is let go is remembered. */
    boolean mouseDragged(MouseButtonEvent event, int screenWidth, int screenHeight) {
        if (grabX < 0) return false;
        int nx = Math.clamp((int) event.x() - grabX, 0, Math.max(0, screenWidth - WIDTH));
        int ny = Math.clamp((int) event.y() - grabY, 0, Math.max(0, screenHeight - HEIGHT));
        savedX = nx - left;
        savedY = ny - top;
        return true;
    }

    boolean mouseReleased() {
        boolean held = grabX >= 0;
        grabX = -1;
        return held;
    }
}
