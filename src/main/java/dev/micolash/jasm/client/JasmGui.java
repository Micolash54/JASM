package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The look shared by every JASM screen: Catppuccin Mocha colours with Minecraft-style bevels. Panels, slots, wells,
 * bars and scroll handles are GUI sprites that stretch to any size.
 */
public final class JasmGui {
    public static final int TEXT = 0xFFCDD6F4;
    public static final int SUBTEXT = 0xFFA6ADC8;
    public static final int MUTED = 0xFF7F849C;
    public static final int ACCENT = 0xFFB4BEFE;
    public static final int GOOD = 0xFFA6E3A1;
    public static final int BAD = 0xFFF38BA8;
    public static final int WARN = 0xFFF9E2AF;
    public static final int SELECTED = 0xFF45475A;
    /** Laid over a row or slot under the mouse: a faint lavender. */
    public static final int HOVER = 0x33B4BEFE;
    private static final int HOVER_RIM = 0xCCB4BEFE;
    private static final int WELL = 0xFF1E1E2E;
    public static final int SHADE = 0xB011111B;
    private static final int NOTICE = 0xE811111B;
    private static final int SHADOW = 0x6E000000;
    private static final float ITEM_COUNT_SCALE = 0.7F;

    private static final Identifier SLOT = Jasm.id("slot");
    private static final Identifier INSET = Jasm.id("inset");
    private static final Identifier INTERFERENCE = Jasm.id("dimensional_interference");
    private static final Identifier BAR_FILL = Jasm.id("bar_fill");
    private static final Identifier KNOB = Jasm.id("button");
    private static final Identifier KNOB_HIGHLIGHTED = Jasm.id("button_highlighted");
    private static final Identifier KNOB_PRESSED = Jasm.id("button_pressed");
    private static final Identifier KNOB_DISABLED = Jasm.id("button_disabled");
    private static final Identifier TRACK = Jasm.id("scroll_track");

    private JasmGui() {}

    /** A switch's label in green when it is on or allows, red when it is off or denies. */
    public static Component state(Component label, boolean on) {
        return label.copy().withColor((on ? GOOD : BAD) & 0xFFFFFF);
    }

    /** Side keys stand in a column just past a panel's right edge, like the Deck's tabs. */
    public static final int SIDE_KEY_WIDTH = 21;
    public static final int SIDE_KEY_HEIGHT = 22;
    public static final int SIDE_KEY_Y = 29;

    /** Where side key {@code i} starts down the screen; neighbours share a row of pixels. */
    public static int sideKeyY(int i) {
        return SIDE_KEY_Y + i * (SIDE_KEY_HEIGHT - 1);
    }

    /**
     * The strip behind {@code keys} side keys whose left edge is at {@code keyX}, as a frame rectangle relative to the
     * screen. It tucks under the panel it hangs from, so the two are drawn as one shape.
     */
    public static int[] sideStrip(int keyX, int keys) {
        return new int[] {keyX - 14, SIDE_KEY_Y - 4, 14 + SIDE_KEY_WIDTH + 3, keys * (SIDE_KEY_HEIGHT - 1) + 10};
    }

    private static final java.util.Map<Long, JasmFrame> FRAMES = new java.util.HashMap<>();

    /** A plain rounded panel, as the base of a screen or a window that stays put. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        FRAMES.computeIfAbsent((long) width << 32 | height, key -> JasmFrame.rounded(new int[] {0, 0, width, height}))
                .draw(graphics, x, y);
    }

    /**
     * A window laid over a screen: the rounded panel with a soft shadow down and to the right, rounded the same way, so
     * it reads as something that can be picked up and moved.
     */
    public static void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        int sx = x + 3;
        int sy = y + 3;
        graphics.fill(sx + 2, sy, sx + width - 2, sy + 1, SHADOW);
        graphics.fill(sx + 1, sy + 1, sx + width - 1, sy + 2, SHADOW);
        graphics.fill(sx, sy + 2, sx + width, sy + height - 2, SHADOW);
        graphics.fill(sx + 1, sy + height - 2, sx + width - 1, sy + height - 1, SHADOW);
        graphics.fill(sx + 2, sy + height - 1, sx + width - 2, sy + height, SHADOW);
        panel(graphics, x, y, width, height);
    }

    /** The 18 × 18 frame behind a slot whose item sits at ({@code itemX}, {@code itemY}). */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, itemX - 1, itemY - 1, 18, 18);
    }

    /** Under a hovered slot's item: covers the game's white highlight with the slot's own colour and a lavender tint. */
    public static void slotHover(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.fill(itemX, itemY, itemX + 16, itemY + 16, WELL);
        graphics.fill(itemX, itemY, itemX + 16, itemY + 16, HOVER);
    }

    /** Over a hovered slot: a lavender rim on its frame, leaving the item itself clear. */
    public static void slotHoverFrame(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        rim(graphics, itemX - 1, itemY - 1, 18, 18, HOVER_RIM);
    }

    /** A carved line across a panel, {@code width} long: dark with a light line under it. */
    public static void divider(GuiGraphicsExtractor graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, 0xFF181825);
        graphics.fill(x, y + 1, x + width, y + 2, 0xFF45475A);
    }

    /** A one-pixel outline. */
    public static void rim(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** Smaller counts anchored to the bottom right of a Deck item. */
    public static void itemCount(GuiGraphicsExtractor graphics, Font font, String count, int itemX, int itemY) {
        if (count.isEmpty()) return;
        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(itemX + 17, itemY + 16);
        graphics.pose().scale(ITEM_COUNT_SCALE, ITEM_COUNT_SCALE);
        graphics.text(font, count, -font.width(count), -font.lineHeight + 1, 0xFFFFFFFF, true);
        graphics.pose().popMatrix();
    }

    /** A recessed well: the item grid, the wafer list, bar and scroll tracks. */
    public static void inset(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, y, width, height);
    }

    /** Animated static inside the usual recessed frame. */
    public static void interference(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        inset(graphics, x, y, width, height);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INTERFERENCE, x + 1, y + 1, width - 2, height - 2);
    }

    /** A charge bar: a well with a fill {@code fraction} of the way across. */
    public static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, double fraction) {
        inset(graphics, x, y, width, height);
        int filled = (int) Math.round((width - 2) * Math.clamp(fraction, 0.0, 1.0));
        if (filled > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_FILL, x + 1, y + 1, filled, height - 2);
        }
    }

    /**
     * A bar with its reading written across it: light where the bar is empty and dark where it is filled, so the
     * words stay readable at any charge.
     */
    public static void labelledBar(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int height, double fraction) {
        bar(graphics, x, y, width, height, fraction);
        int tx = x + (width - font.width(text)) / 2;
        int ty = y + (height - 8) / 2;
        int filled = x + 1 + (int) Math.round((width - 2) * Math.clamp(fraction, 0.0, 1.0));
        graphics.enableScissor(filled, y, x + width, y + height);
        graphics.text(font, text, tx, ty, TEXT, false);
        graphics.disableScissor();
        graphics.enableScissor(x, y, filled, y + height);
        graphics.text(font, text, tx, ty, 0xFF11111B, false);
        graphics.disableScissor();
    }

    /**
     * A slider: a well filled lavender up to a raised knob {@code knobWidth} wide. The knob lights up under the mouse
     * and sinks in while it is dragged.
     */
    public static void slider(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int knobWidth, double fraction,
            boolean hovered, boolean dragging) {
        inset(graphics, x, y, width, height);
        int knobX = x + (int) Math.round((width - knobWidth) * Math.clamp(fraction, 0.0, 1.0));
        if (knobX > x + 1) graphics.fill(x + 1, y + 1, knobX + knobWidth / 2, y + height - 1, 0x66B4BEFE);
        Identifier knob = dragging ? KNOB_PRESSED : hovered ? KNOB_HIGHLIGHTED : KNOB;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, knob, knobX, y - 3, knobWidth, height + 6);
    }

    /**
     * A short message laid over the bottom of a list or grid, {@code x} to {@code x + width} and ending at
     * {@code bottom}: a dark strip with up to three centred lines, green when something worked and red when not.
     */
    public static void notice(GuiGraphicsExtractor graphics, Font font, Component message, boolean ok, int x, int bottom, int width) {
        List<FormattedCharSequence> lines = font.split(message, width - 8);
        int count = Math.min(3, lines.size());
        int top = bottom - count * 9 - 5;
        int color = ok ? GOOD : BAD;
        graphics.fill(x, top, x + width, bottom, NOTICE);
        graphics.fill(x, top, x + width, top + 1, color);
        for (int i = 0; i < count; i++) {
            graphics.text(font, lines.get(i), x + (width - font.width(lines.get(i))) / 2, top + 3 + i * 9, color, false);
        }
    }

    /** The green part of a charge bar, {@code width} long. */
    public static void barFill(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_FILL, x, y, width, height);
    }

    /**
     * A rounded scroll track ten pixels wide with a key for a handle, {@code handleOffset} pixels down. The key is one
     * pixel wider than the track on each side; it lights up under the mouse, sinks in while dragged and is dimmed when
     * there is nothing to scroll.
     */
    public static void track(GuiGraphicsExtractor graphics, int x, int y, int height, int handleOffset, int handleHeight, boolean active) {
        drawTrack(graphics, x, y, 10, height, y + handleOffset, handleHeight, active);
    }

    /**
     * The same rounded track and key as the Deck's, for lists whose handle moves between one pixel below the top and
     * one pixel above the bottom. The key covers those spare pixels so it still reaches both ends.
     */
    public static void scrollBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int handleOffset, int handleHeight,
            boolean active) {
        drawTrack(graphics, x, y, width, height, y + handleOffset, handleHeight + 2, active);
    }

    private static void drawTrack(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int hy, int handleHeight,
            boolean active) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TRACK, x, y, width, height);
        Identifier sprite = KNOB_DISABLED;
        if (active) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var window = minecraft.getWindow();
            double mx = minecraft.mouseHandler.getScaledXPos(window);
            double my = minecraft.mouseHandler.getScaledYPos(window);
            boolean onTrack = mx >= x - 1 && mx < x + width + 1 && my >= y && my < y + height;
            boolean onHandle = onTrack && my >= hy && my < hy + handleHeight;
            sprite = onTrack && minecraft.mouseHandler.isLeftPressed() ? KNOB_PRESSED : onHandle ? KNOB_HIGHLIGHTED : KNOB;
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x - 1, hy, width + 2, handleHeight);
    }
}
