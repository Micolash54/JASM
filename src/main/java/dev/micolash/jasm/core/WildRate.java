package dev.micolash.jasm.core;

/** How likely a growing Seeded Amethyst is to draw a wild Bitling over on one of its random ticks. */
public final class WildRate {
    /** Average ticks between tries at 100%. */
    public static final int BASE_INTERVAL = 6_000;

    private WildRate() {}

    public static double chancePerRandomTick(int randomTickSpeed, int percent) {
        if (percent <= 0) {
            return 0;
        }
        double ticksBetweenRandomTicks = 4096.0 / Math.max(1, randomTickSpeed);
        return ticksBetweenRandomTicks * percent / (BASE_INTERVAL * 100.0);
    }
}
