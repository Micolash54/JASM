package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.FaceMode;
import dev.micolash.jasm.network.MachineFace;
import dev.micolash.jasm.network.MachineSides;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * A machine's I/O grid, in a window like the Deck Link one: opened from a side key, drawn above the screen and moved by
 * dragging any empty spot of it. Six face keys stand in a plus with the back in the bottom-right corner; each shows its
 * mode by colour only and steps to the next mode when clicked. Machines with a tank get a key under the title that
 * switches between the items' faces and the fluids'.
 */
final class IoGridWindow {
    static final int WIDTH = 100;
    private static final int FACE = 18;
    private static final int PITCH = 21;
    private static final int GRID_X = (WIDTH - 2 * PITCH - FACE) / 2;
    private static final int SWITCH_Y = 26;
    private static final int SWITCH_HEIGHT = 16;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final JasmButton.Icon ICON = new JasmButton.Icon(Jasm.id("icon/io_grid"), 11, 11);
    private static final Identifier NONE = Jasm.id("button");
    private static final Identifier NONE_LIT = Jasm.id("button_highlighted");
    /** Where each face sits in the plus, in columns and rows of the 3×3. */
    private static final int[][] CELLS = {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {2, 2}};

    private final Font font;
    private final boolean fluids;
    private final ToIntFunction<MachineSides.Kind> modes;
    private final IntConsumer press;
    private final JasmButton close;
    private final @Nullable JasmButton kindSwitch;
    private @Nullable JasmButton key;
    private MachineSides.Kind kind = MachineSides.Kind.ITEMS;
    private boolean open;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    /** {@code modes} gives a kind's faces as the menu packs them; {@code press} sends a face button's id. */
    IoGridWindow(Font font, boolean fluids, ToIntFunction<MachineSides.Kind> modes, IntConsumer press) {
        this.font = font;
        this.fluids = fluids;
        this.modes = modes;
        this.press = press;
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
        kindSwitch = fluids ? JasmButton.text(kindLabel(), b -> switchKind(), 0, 0, WIDTH - 16, SWITCH_HEIGHT) : null;
    }

    /** The side key that opens and shuts the window; the screen adds it. */
    JasmButton key(int keyX, int keyY, int top) {
        Component label = Component.translatable("screen.jasm.io.title");
        key = JasmButton.icon(() -> ICON, label, b -> toggle(keyX, top), keyX, keyY, JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT);
        key.setTooltip(Tooltip.create(label));
        key.setLatched(open);
        return key;
    }

    /** "< Items >": what is being set, and that a click swaps it. */
    private Component kindLabel() {
        return Component.literal("< ")
                .append(Component.translatable(kind == MachineSides.Kind.ITEMS ? "screen.jasm.io.items" : "screen.jasm.io.fluids"))
                .append(" >");
    }

    private void switchKind() {
        kind = kind == MachineSides.Kind.ITEMS ? MachineSides.Kind.FLUIDS : MachineSides.Kind.ITEMS;
        if (kindSwitch != null) kindSwitch.setMessage(kindLabel());
    }

    boolean isOpen() {
        return open;
    }

    /** Opens the window just left of the side key at {@code keyX}, a little below the screen's top; or shuts it. */
    private void toggle(int keyX, int top) {
        if (open) {
            close();
        } else {
            open = true;
            x = keyX - 6 - WIDTH;
            y = top + 40;
            if (key != null) key.setLatched(true);
        }
    }

    void close() {
        open = false;
        grabX = -1;
        if (key != null) key.setLatched(false);
    }

    private int gridY() {
        return fluids ? SWITCH_Y + SWITCH_HEIGHT + 5 : 28;
    }

    int height() {
        return gridY() + 2 * PITCH + FACE + 8;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    /** The face key under the mouse, or null. */
    private @Nullable MachineFace faceAt(double mouseX, double mouseY) {
        if (!open) return null;
        for (MachineFace face : MachineFace.values()) {
            int fx = x + GRID_X + CELLS[face.ordinal()][0] * PITCH;
            int fy = y + gridY() + CELLS[face.ordinal()][1] * PITCH;
            if (mouseX >= fx && mouseX < fx + FACE && mouseY >= fy && mouseY < fy + FACE) return face;
        }
        return null;
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) return;
        close.setPosition(x + WIDTH - 16, y + 5);
        if (kindSwitch != null) kindSwitch.setPosition(x + 8, y + SWITCH_Y);
        MachineFace hovered = faceAt(mouseX, mouseY);
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, height());
        graphics.text(font, Component.translatable("screen.jasm.io.title"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        int packed = modes.applyAsInt(kind);
        for (MachineFace face : MachineFace.values()) {
            int fx = x + GRID_X + CELLS[face.ordinal()][0] * PITCH;
            int fy = y + gridY() + CELLS[face.ordinal()][1] * PITCH;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(MachineSides.unpack(packed, face), face == hovered), fx, fy, FACE, FACE);
        }
        close.extractRenderState(graphics, mouseX, mouseY, a);
        if (kindSwitch != null) kindSwitch.extractRenderState(graphics, mouseX, mouseY, a);
        if (hovered != null) {
            FaceMode mode = MachineSides.unpack(packed, hovered);
            Component name = Component.translatable("screen.jasm.io.face." + hovered.name().toLowerCase(Locale.ROOT));
            Component said = Component.translatable("screen.jasm.io.mode." + mode.name().toLowerCase(Locale.ROOT))
                    .withColor(color(mode) & 0xFFFFFF);
            graphics.setTooltipForNextFrame(font, List.of(name.getVisualOrderText(), said.getVisualOrderText()), mouseX, mouseY);
        }
    }

    private static Identifier sprite(FaceMode mode, boolean lit) {
        return switch (mode) {
            case NONE -> lit ? NONE_LIT : NONE;
            case INPUT -> Jasm.id(lit ? "io_input_highlighted" : "io_input");
            case OUTPUT -> Jasm.id(lit ? "io_output_highlighted" : "io_output");
            case BOTH -> Jasm.id(lit ? "io_both_highlighted" : "io_both");
        };
    }

    /** The colour a mode's name is written in: the colour of its key. */
    private static int color(FaceMode mode) {
        return switch (mode) {
            case NONE -> JasmGui.SUBTEXT;
            case INPUT -> JasmGui.GOOD;
            case OUTPUT -> JasmGui.BAD;
            case BOTH -> JasmGui.WARN;
        };
    }

    /** True when the click was inside the window. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || kindSwitch != null && kindSwitch.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        MachineFace face = faceAt(event.x(), event.y());
        if (face != null) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            press.accept(MachineSides.button(kind, face));
        } else if (grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key picks it up. */
    private boolean grabbable(double mx, double my) {
        return contains(mx, my) && faceAt(mx, my) == null && !close.isMouseOver(mx, my)
                && (kindSwitch == null || !kindSwitch.isMouseOver(mx, my));
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
