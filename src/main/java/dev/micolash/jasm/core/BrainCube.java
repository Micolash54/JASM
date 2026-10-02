package dev.micolash.jasm.core;

import org.jspecify.annotations.Nullable;

/** Finds the cube of Network Chambers a brain sits in: 3×3×3 first, then 2×2×2, the brain anywhere inside. */
public final class BrainCube {
    private BrainCube() {}

    /** Whether a chamber this brain may use stands at a spot. */
    @FunctionalInterface
    public interface Cells {
        boolean chamber(int x, int y, int z);
    }

    /** A cube by its lowest corner and side. */
    public record Box(int x, int y, int z, int side) {
        public boolean contains(int px, int py, int pz) {
            return px >= x && px < x + side && py >= y && py < y + side && pz >= z && pz < z + side;
        }

        public BrainSize size() {
            return BrainSize.ofSide(side);
        }
    }

    public static @Nullable Box find(int bx, int by, int bz, Cells cells) {
        for (int side = 3; side >= 2; side--) {
            Box box = find(bx, by, bz, side, cells);
            if (box != null) {
                return box;
            }
        }
        return null;
    }

    private static @Nullable Box find(int bx, int by, int bz, int side, Cells cells) {
        for (int oy = by - side + 1; oy <= by; oy++) {
            for (int oz = bz - side + 1; oz <= bz; oz++) {
                for (int ox = bx - side + 1; ox <= bx; ox++) {
                    if (complete(ox, oy, oz, side, bx, by, bz, cells)) {
                        return new Box(ox, oy, oz, side);
                    }
                }
            }
        }
        return null;
    }

    private static boolean complete(int ox, int oy, int oz, int side, int bx, int by, int bz, Cells cells) {
        for (int y = oy; y < oy + side; y++) {
            for (int z = oz; z < oz + side; z++) {
                for (int x = ox; x < ox + side; x++) {
                    if ((x != bx || y != by || z != bz) && !cells.chamber(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
