package dev.micolash.jasm.core;

/**
 * Where a wafer record lives in the region files. Serial n (from 1) takes slot n - 1; each region file holds
 * 32 x 32 = 1,024 slots and the files run along x (r.0.0, r.1.0, ...).
 */
public final class SerialLayout {
    public static final int SLOTS_PER_REGION = 1024;

    private SerialLayout() {}

    /** Chunk-grid position of a serial's slot. */
    public record Slot(int x, int z) {}

    public static Slot slot(long serial) {
        if (serial < 1) {
            throw new IllegalArgumentException("Serials start at 1: " + serial);
        }
        long index = serial - 1;
        long region = index / SLOTS_PER_REGION;
        int local = (int) (index % SLOTS_PER_REGION);
        if (region > Integer.MAX_VALUE / 32 - 1) {
            throw new IllegalArgumentException("Serial out of range: " + serial);
        }
        return new Slot((int) region * 32 + local % 32, local / 32);
    }
}
