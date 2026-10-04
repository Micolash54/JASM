package dev.micolash.jasm.bay;

/**
 * How a Bitling's work clip lines up with the bay's cycle, so the hit always lands as the block is placed or broken.
 * The clip plays at its own speed and ends on the hit; a longer cycle waits in the start pose first.
 */
public final class BayTiming {
    /** The work clips are one second long. */
    public static final int CLIP_TICKS = 20;

    private BayTiming() {}

    /** Cycles shorter than one clip are shared by two Bitlings taking turns, so neither has to rush. */
    public static boolean pair(int cycleTicks) {
        return cycleTicks < CLIP_TICKS;
    }

    /** How long one Bitling's stretch of work lasts: one cycle alone, two when taking turns. */
    public static float window(int cycleTicks, boolean pair) {
        return pair ? 2F * cycleTicks : cycleTicks;
    }

    /**
     * How far through its stretch of work (0 to 1) a Bitling is, {@code cycle} (0 to 1) through the running cycle. Taking
     * turns, the one whose turn it is does the second half and the other gets ready with the first.
     */
    public static float progress(float cycle, boolean pair, boolean myTurn) {
        return !pair ? cycle : myTurn ? 0.5F + 0.5F * cycle : 0.5F * cycle;
    }

    /** How far through the work clip (0 to 1), {@code progress} through a stretch of work {@code windowTicks} long. */
    public static float clip(float progress, float windowTicks) {
        if (windowTicks <= CLIP_TICKS) return progress;
        return Math.max(0, 1 - (1 - progress) * windowTicks / CLIP_TICKS);
    }
}
