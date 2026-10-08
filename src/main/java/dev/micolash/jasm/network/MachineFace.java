package dev.micolash.jasm.network;

import net.minecraft.core.Direction;

/**
 * A face of a machine as seen standing in front of it, looking at its front. Turning the machine turns its faces with
 * it. A machine facing up or down is looked at with north at the top.
 */
public enum MachineFace {
    TOP,
    LEFT,
    FRONT,
    RIGHT,
    BOTTOM,
    BACK;

    private static final MachineFace[] VALUES = values();

    public static MachineFace byId(int id) {
        return VALUES[Math.floorMod(id, VALUES.length)];
    }

    /** The side of the block this face is on, for a machine whose front is {@code front}. */
    public Direction toWorld(Direction front) {
        boolean vertical = front.getAxis().isVertical();
        return switch (this) {
            case FRONT -> front;
            case BACK -> front.getOpposite();
            case TOP -> vertical ? Direction.NORTH : Direction.UP;
            case BOTTOM -> vertical ? Direction.SOUTH : Direction.DOWN;
            // the viewer's left: for a front facing north they stand north, looking south, with east on their left
            case LEFT -> front == Direction.DOWN ? Direction.EAST : front == Direction.UP ? Direction.WEST : front.getClockWise();
            case RIGHT -> front == Direction.DOWN ? Direction.WEST : front == Direction.UP ? Direction.EAST : front.getCounterClockWise();
        };
    }

    /** The face on {@code side} of a machine whose front is {@code front}. */
    public static MachineFace of(Direction front, Direction side) {
        for (MachineFace face : VALUES) {
            if (face.toWorld(front) == side) return face;
        }
        return FRONT;
    }
}
