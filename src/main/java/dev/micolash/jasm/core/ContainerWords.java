package dev.micolash.jasm.core;

/** A screen's synced numbers travel as 16-bit shorts, so a bigger one goes as two halves. */
public final class ContainerWords {
    private ContainerWords() {}

    /** The lower 16 bits of {@code value}. */
    public static int low(int value) {
        return value & 0xFFFF;
    }

    /** The upper 16 bits of {@code value}. */
    public static int high(int value) {
        return value >>> 16;
    }

    /** The number sent as the two halves {@code high} and {@code low}. */
    public static int join(int high, int low) {
        return (high & 0xFFFF) << 16 | (low & 0xFFFF);
    }
}
