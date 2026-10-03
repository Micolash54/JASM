package dev.micolash.jasm.core;

import java.util.function.IntPredicate;

/**
 * Brain floors stacked straight on top of each other make a tower. A run taller than the height limit is cut into towers
 * from the bottom up, so the floors past the limit make a tower of their own.
 */
public final class BrainTower {
    private BrainTower() {}

    /** A tower by the height of its lowest floor and how many floors it has; 0 floors for a brain that isn't a floor. */
    public record Tower(int baseY, int floors) {}

    /** The tower the floor at {@code y} belongs to. {@code floorAt} says where the column holds a complete floor. */
    public static Tower of(int y, IntPredicate floorAt, int minY, int maxY, int maxFloors) {
        if (!floorAt.test(y)) {
            return new Tower(y, 0);
        }
        int bottom = y;
        while (bottom > minY && floorAt.test(bottom - 1)) {
            bottom--;
        }
        int top = y;
        while (top < maxY && floorAt.test(top + 1)) {
            top++;
        }
        return inRun(y, bottom, top, maxFloors);
    }

    /** The tower the floor at {@code y} belongs to, in an unbroken run of floors from {@code bottom} to {@code top}. */
    public static Tower inRun(int y, int bottom, int top, int maxFloors) {
        int size = Math.max(1, maxFloors);
        int base = bottom + (y - bottom) / size * size;
        return new Tower(base, Math.min(size, top - base + 1));
    }
}
