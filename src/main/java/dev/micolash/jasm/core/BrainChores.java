package dev.micolash.jasm.core;

/**
 * What each Bitling inside an awake Network Brain is busy with. Time is cut into shifts, each Bitling's a little out of
 * step with the next; every shift it picks working or a short rest.
 */
public final class BrainChores {
    /** How long one shift lasts, in seconds. */
    public static final double SHIFT_SECONDS = 9;
    /** How far apart the Bitlings' shifts start, in seconds. */
    public static final double STAGGER_SECONDS = 3.7;

    private BrainChores() {}

    /** The shift Bitling {@code bitling} is in at {@code seconds}. */
    public static long shift(double seconds, int bitling) {
        return (long) Math.floor((seconds + bitling * STAGGER_SECONDS) / SHIFT_SECONDS);
    }

    /** When a shift of Bitling {@code bitling} begins, in seconds. */
    public static double shiftStart(long shift, int bitling) {
        return shift * SHIFT_SECONDS - bitling * STAGGER_SECONDS;
    }

    /** The loop Bitling {@code bitling} plays during a shift: two times in three it works, otherwise it idles. */
    public static String loop(long shift, int bitling) {
        return Math.floorMod(Long.hashCode(shift * 31 + bitling), 3) == 2 ? "idle" : "working";
    }
}
