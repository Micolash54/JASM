package dev.micolash.jasm.core;

/**
 * Two numbers written on one line of a screen: when the line is too wide, which of them to abbreviate. The longer one
 * goes first, and only when that is not enough does the other follow, so a number that fits keeps all its digits.
 */
public final class ReadingFit {
    /** Which of the two are written short. */
    public record Shortened(boolean first, boolean second) {
        public static final Shortened NONE = new Shortened(false, false);
        public static final Shortened FIRST = new Shortened(true, false);
        public static final Shortened SECOND = new Shortened(false, true);
        public static final Shortened BOTH = new Shortened(true, true);

        public boolean any() {
            return first || second;
        }
    }

    /** The width of the whole line with the first and the second written short or in full. */
    @FunctionalInterface
    public interface Measure {
        int width(boolean firstShort, boolean secondShort);
    }

    private ReadingFit() {}

    public static Shortened fit(long first, long second, int room, Measure measure) {
        if (measure.width(false, false) <= room) {
            return Shortened.NONE;
        }
        int a = digits(first);
        int b = digits(second);
        if (a != b) {
            Shortened longer = a > b ? Shortened.FIRST : Shortened.SECOND;
            if (measure.width(longer.first(), longer.second()) <= room) {
                return longer;
            }
        }
        return Shortened.BOTH;
    }

    private static int digits(long value) {
        return Long.toString(Math.abs(value)).length();
    }
}
