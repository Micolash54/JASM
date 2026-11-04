package dev.micolash.jasm.client;

/**
 * The short texts on the Advanced Crafting Server's front screen, written with its 3x5 glyph sheet: at most four glyphs
 * for an amount, five for the crafts running.
 */
public final class ServerScreenText {
    /** The glyph sheet's order. */
    private static final String GLYPHS = "0123456789x%/-kM>#<";
    /** Segments in the core bar. */
    public static final int SEGMENTS = 8;
    private static final int MAX_RUNNING = 5;

    private ServerScreenText() {}

    /**
     * How many the job makes: items as "x64", "1234", "12k", "1M"; a fluid in whole buckets with no unit, "8", "<1" under
     * one, then "12k" and "1M" the same way. Always rounded down.
     */
    public static String amount(long count, boolean fluid) {
        if (fluid) {
            if (count > 0 && count < 1000) return "<1";
            return shortNumber(count / 1000);
        }
        return count < 1000 ? "x" + Math.max(0, count) : shortNumber(count);
    }

    private static String shortNumber(long n) {
        if (n < 10_000) return Long.toString(Math.max(0, n));
        if (n < 1_000_000) return n / 1000 + "k";
        return Math.min(n / 1_000_000, 999) + "M";
    }

    public static String percent(int percent) {
        return Math.clamp(percent, 0, 100) + "%";
    }

    /** "running/max", or only the running count when that would be over five glyphs. */
    public static String running(int running, int max) {
        String both = running + "/" + max;
        return both.length() > MAX_RUNNING ? Integer.toString(running) : both;
    }

    /**
     * Lit segments of the core bar: one per craft running while the server runs at most eight at once; past eight, the
     * share of crafts running, out of eight.
     */
    public static int litSegments(int running, int max) {
        if (max <= SEGMENTS) return Math.clamp(running, 0, SEGMENTS);
        return Math.clamp(Math.round(SEGMENTS * running / (float) max), 0, SEGMENTS);
    }

    /** The glyph's cell on the sheet, or -1 for a character the sheet doesn't have. */
    public static int glyph(char c) {
        return GLYPHS.indexOf(c);
    }

    /** The solid glyph, sampled to draw bars and frames. */
    public static int solid() {
        return glyph('#');
    }
}
