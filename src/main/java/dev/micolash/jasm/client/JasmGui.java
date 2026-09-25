package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

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
    public static final int SELECTED = 0xFF45475A;
    public static final int HOVER = 0x30FFFFFF;
    public static final int SHADE = 0xB011111B;

    private static final Identifier PANEL = Jasm.id("panel");
    private static final Identifier SLOT = Jasm.id("slot");
    private static final Identifier INSET = Jasm.id("inset");
    private static final Identifier BAR_FILL = Jasm.id("bar_fill");
    private static final Identifier HANDLE = Jasm.id("scroll_handle");
    private static final Identifier HANDLE_DISABLED = Jasm.id("scroll_handle_disabled");

    private JasmGui() {}

    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, width, height);
    }

    /** The 18 × 18 frame behind a slot whose item sits at ({@code itemX}, {@code itemY}). */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, itemX - 1, itemY - 1, 18, 18);
    }

    /** A recessed well: the item grid, the wafer list, bar and scroll tracks. */
    public static void inset(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, y, width, height);
    }

    /** A charge bar: a well with a fill {@code fraction} of the way across. */
    public static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, double fraction) {
        inset(graphics, x, y, width, height);
        int filled = (int) Math.round((width - 2) * Math.clamp(fraction, 0.0, 1.0));
        if (filled > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_FILL, x + 1, y + 1, filled, height - 2);
        }
    }

    /** A scroll track with its handle {@code handleOffset} pixels down; greyed out when there is nothing to scroll. */
    public static void scrollBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int handleOffset, int handleHeight,
            boolean active) {
        inset(graphics, x, y, width, height);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, active ? HANDLE : HANDLE_DISABLED, x + 1, y + 1 + handleOffset, width - 2, handleHeight);
    }
}
