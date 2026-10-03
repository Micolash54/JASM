package dev.micolash.jasm.core;

/** A brain floor: a Network Brain with a Network Chamber on each of the 8 spots round it, on the same layer. */
public final class BrainFloor {
    private BrainFloor() {}

    /** Whether a chamber this brain may use stands at a spot. */
    @FunctionalInterface
    public interface Cells {
        boolean chamber(int x, int y, int z);
    }

    public static boolean complete(int bx, int by, int bz, Cells cells) {
        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                if ((x != bx || z != bz) && !cells.chamber(x, by, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether a spot is part of the floor round a brain, the brain's own spot included. */
    public static boolean contains(int bx, int by, int bz, int x, int y, int z) {
        return y == by && Math.abs(x - bx) <= 1 && Math.abs(z - bz) <= 1;
    }
}
