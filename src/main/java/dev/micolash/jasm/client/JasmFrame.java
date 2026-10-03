package dev.micolash.jasm.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Several panels drawn as one: the rectangles are joined into a single shape with one outline, rounded outer corners,
 * a light line under the top and a dark band along the bottom. Where a side panel meets the main one the joint stays
 * square. Build it once per layout; drawing is a handful of fills.
 */
public final class JasmFrame {
    private static final int OUTLINE = 0xFF11111B;
    private static final int LIGHT = 0xFF585B70;
    private static final int BODY = 0xFF313244;
    private static final int DARK = 0xFF181825;
    /** Filled rectangles {x, y, width, height, colour}. */
    private final List<int[]> runs = new ArrayList<>();

    private JasmFrame() {}

    private static boolean inside(boolean[] in, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && in[y * w + x];
    }

    /**
     * Rectangles given as {x, y, width, height}, relative to the screen's corner, joined into one shape whose outer
     * corners are rounded off by two pixels. The dark band along the bottom curves up into the rounded corners.
     */
    public static JasmFrame rounded(int[]... rects) {
        JasmFrame frame = new JasmFrame();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] r : rects) {
            if (r[2] <= 0 || r[3] <= 0) continue;
            minX = Math.min(minX, r[0]);
            minY = Math.min(minY, r[1]);
            maxX = Math.max(maxX, r[0] + r[2]);
            maxY = Math.max(maxY, r[1] + r[3]);
        }
        if (maxX <= minX) return frame;
        int w = maxX - minX;
        int h = maxY - minY;
        boolean[] in = new boolean[w * h];
        for (int[] r : rects) {
            if (r[2] <= 0 || r[3] <= 0) continue;
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                for (int x = r[0]; x < r[0] + r[2]; x++) in[(y - minY) * w + x - minX] = true;
            }
        }
        int[] color = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (in[y * w + x]) color[y * w + x] = edge(in, w, h, x, y) ? OUTLINE : BODY;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (color[i] != BODY) continue;
                if (y > 0 && color[i - w] == OUTLINE) color[i] = LIGHT;
                else if (y + 1 < h && color[i + w] == OUTLINE || y + 2 < h && color[i + 2 * w] == OUTLINE) color[i] = DARK;
            }
        }
        // Outer corners: two pixels cut off, the outline stepped in, the light or dark line following it round.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!in[y * w + x]) continue;
                for (int dy = -1; dy <= 1; dy += 2) {
                    for (int dx = -1; dx <= 1; dx += 2) {
                        if (inside(in, w, h, x + dx, y) || inside(in, w, h, x, y + dy)) continue;
                        int ix = -dx;
                        int iy = -dy;
                        set(color, w, h, x, y, 0);
                        set(color, w, h, x + ix, y, 0);
                        set(color, w, h, x, y + iy, 0);
                        set(color, w, h, x + ix, y + iy, OUTLINE);
                        set(color, w, h, x, y + 2 * iy, OUTLINE);
                        if (dy < 0) {
                            set(color, w, h, x + ix, y + 2 * iy, LIGHT);
                        } else {
                            set(color, w, h, x + ix, y + 3 * iy, DARK);
                            set(color, w, h, x + ix, y + 4 * iy, DARK);
                            set(color, w, h, x + 2 * ix, y + 3 * iy, DARK);
                        }
                    }
                }
            }
        }
        // Runs of one colour along each row, stacked into rectangles where rows repeat.
        List<int[]> open = new ArrayList<>();
        for (int y = 0; y <= h; y++) {
            List<int[]> row = new ArrayList<>();
            if (y < h) {
                int start = 0;
                for (int x = 1; x <= w; x++) {
                    if (x == w || color[y * w + x] != color[y * w + start]) {
                        if (color[y * w + start] != 0) row.add(new int[]{start + minX, y + minY, x - start, 1, color[y * w + start]});
                        start = x;
                    }
                }
            }
            List<int[]> next = new ArrayList<>();
            for (int[] run : row) {
                int[] same = null;
                for (int[] o : open) {
                    if (o[0] == run[0] && o[2] == run[2] && o[4] == run[4]) same = o;
                }
                if (same != null) {
                    same[3]++;
                    open.remove(same);
                    next.add(same);
                } else {
                    next.add(run);
                }
            }
            frame.runs.addAll(open);
            open = next;
        }
        return frame;
    }

    private static boolean edge(boolean[] in, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!inside(in, w, h, x + dx, y + dy)) return true;
            }
        }
        return false;
    }

    private static void set(int[] color, int w, int h, int x, int y, int value) {
        if (x >= 0 && y >= 0 && x < w && y < h) color[y * w + x] = value;
    }

    public void draw(GuiGraphicsExtractor graphics, int left, int top) {
        for (int[] r : runs) graphics.fill(left + r[0], top + r[1], left + r[0] + r[2], top + r[1] + r[3], r[4]);
    }
}
