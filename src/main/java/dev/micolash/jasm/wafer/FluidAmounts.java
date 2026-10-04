package dev.micolash.jasm.wafer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Fluid amounts are kept in millibuckets. Wafer sizes count buckets, and one transfer share is an eighth of a bucket. */
public final class FluidAmounts {
    public static final int PER_BUCKET = 1000;
    /** The fluid that moves for the same charge and port allowance as one item. */
    public static final int PER_SHARE = 125;

    private FluidAmounts() {}

    /** Millibuckets in a wafer size counted in buckets. */
    public static long roomMb(long buckets) {
        return buckets * PER_BUCKET;
    }

    /** Transfer shares needed to move {@code mb}, rounded up. */
    public static long shares(long mb) {
        return (mb + PER_SHARE - 1) / PER_SHARE;
    }

    /** "12.5" or "1,024" for an amount in buckets; at most one decimal. */
    public static String buckets(long mb) {
        DecimalFormat format = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(mb / (double) PER_BUCKET);
    }
}
