package dev.micolash.jasm.relay;

/**
 * How a Relay Chatterbox's dish moves in a player's game. Each move turns and tilts together, as one spring: it sets
 * off cleanly, speeds toward where it is heading, swings past it once on arrival and eases back onto it, then holds
 * still. Nothing turns back on the way there: the tilt keeps above the base all along the route instead of knocking
 * against it. Linked, it points at the partner's dish, or straight up when the partner is in another dimension; with
 * no frequency it goes back to rest; otherwise it searches, swinging to a random spot, pausing, then on to the next,
 * on its own rhythm. Kept by the block in the player's game only: never saved or sent. Angles are in degrees: turn 0
 * faces the block's front, tilt is up from level.
 */
public final class RelayDish {
    public enum Mode { REST, AIM, UP, SEARCH }

    /** The dish as its model has it: where it turns and tilts, its resting tilt and how far it may tilt at each turn. */
    public record Shape(float axisX, float axisZ, float hingeX, float hingeY, float hingeZ, float bowlDrop, float restTilt,
            float lowestTilt, float frontLowestTilt, float frontTurn, float blendTurn, float highestTilt) {
        /** The lowest tilt that keeps the bowl clear of its base at this turn: lower near the front, blending out. */
        public float lowestTilt(float turn) {
            float t = Math.clamp((Math.abs(wrap(turn)) - frontTurn) / (blendTurn - frontTurn), 0F, 1F);
            return frontLowestTilt + (lowestTilt - frontLowestTilt) * t * t * (3 - 2 * t);
        }

        /** The highest of the lowest tilts met turning from {@code a} to {@code b}: the limit only grows away from the front. */
        public float lowestTiltBetween(float a, float b) {
            float lo = Math.min(a, b);
            float hi = Math.max(a, b);
            if (180 + 360 * (float) Math.ceil((lo - 180) / 360) <= hi) {
                return lowestTilt(180);
            }
            return Math.max(lowestTilt(a), lowestTilt(b));
        }
    }

    private static final float STEP = 1 / 120F;
    private static final float FREQUENCY = 1.3F;
    private static final float DAMPING = 0.3F;
    private static final float STIFFNESS = sq(2 * (float) Math.PI * FREQUENCY);
    private static final float DRAG = 2 * DAMPING * 2 * (float) Math.PI * FREQUENCY;
    /** Just enough drag to come back without passing the goal again. */
    private static final float CALM_DRAG = 2 * 2 * (float) Math.PI * FREQUENCY;
    private static final float MAX_TURN_SPEED = 240;
    private static final float MAX_TILT_SPEED = 160;
    /** Searching never tilts higher than this. */
    private static final float SEARCH_TILT = 70;

    private double clock = Double.NaN;
    private float turn, tilt;
    /** The move under way: where it set off, how far it goes on each axis, and where it ends. */
    private float fromTurn, fromTilt, byTurn, byTilt, goalTurn, goalTilt;
    /** How far along the move is (0 to 1, a little past 1 on the swing), how fast that goes, and its top speed. */
    private float progress = 1, speed, top;
    /** Calm once the move has swung past its goal and turned back; arrived once it first got there. */
    private boolean calm = true, arrived = true;
    private boolean still = true;
    private Mode mode = Mode.REST;
    private float aimTurn, aimTilt;
    private float pause;
    private long random;

    public RelayDish(long seed) {
        random = seed * 0x9E3779B97F4A7C15L | 1;
    }

    public float turn() {
        return turn;
    }

    public float tilt() {
        return tilt;
    }

    public boolean still() {
        return still;
    }

    /**
     * Moves the dish on to {@code seconds}. The first call puts it straight where it would settle, so a dish coming
     * into view doesn't swing. For {@link Mode#AIM}, the turn and tilt that point at the partner.
     */
    public void update(double seconds, Mode now, float turnTo, float tiltTo, Shape shape) {
        if (Double.isNaN(clock)) {
            clock = seconds;
            mode = now;
            aimTurn = turnTo;
            aimTilt = tiltTo;
            goal(shape);
            turn = goalTurn = wrap(goalTurn);
            tilt = goalTilt;
            still = true;
            pause = nextPause();
            return;
        }
        if (now != mode || now == Mode.AIM && (turnTo != aimTurn || tiltTo != aimTilt)) {
            mode = now;
            aimTurn = turnTo;
            aimTilt = tiltTo;
            retarget(shape);
        }
        float dt = (float) Math.clamp(seconds - clock, 0, 0.25);
        clock = seconds;
        if (still) {
            if (mode == Mode.SEARCH && (pause -= dt) <= 0) {
                retarget(shape);
            }
            return;
        }
        int steps = dt > 0 ? Math.max(1, Math.round(dt / STEP)) : 0;
        float reach = Math.max(Math.abs(byTurn), Math.abs(byTilt));
        for (int i = 0; i < steps; i++) {
            float drag = calm ? CALM_DRAG : DRAG;
            speed = Math.clamp(speed + (STIFFNESS * (1 - progress) - drag * speed) * STEP, -top, top);
            progress += speed * STEP;
            if (progress >= 1) {
                arrived = true;
            }
            if (!calm && progress > 1 && speed < 0) {
                // swung past and on the way back: the one overshoot is done
                calm = true;
            }
            place(shape);
            if (Math.abs(1 - progress) * reach < 0.05F && Math.abs(speed) * reach < 0.5F) {
                turn = goalTurn = wrap(goalTurn);
                tilt = goalTilt;
                progress = 1;
                speed = 0;
                still = true;
                pause = nextPause();
                break;
            }
        }
    }

    /** Sets off for the goal of the current mode, the short way round. */
    private void retarget(Shape shape) {
        goal(shape);
        // how fast it was already going, in degrees a second, before this move
        float wasTurn = speed * byTurn;
        float wasTilt = speed * byTilt;
        turn = wrap(turn);
        fromTurn = turn;
        fromTilt = tilt;
        goalTurn = turn + wrap(goalTurn - turn);
        // never ask for a tilt below the limit anywhere on the way, so the tilt need not turn back on its way there
        goalTilt = Math.min(shape.highestTilt(), Math.max(goalTilt, shape.lowestTiltBetween(turn, goalTurn)));
        byTurn = goalTurn - fromTurn;
        byTilt = goalTilt - fromTilt;
        float size = byTurn * byTurn + byTilt * byTilt;
        if (size < 1e-6F) {
            progress = 1;
            speed = 0;
            calm = true;
            arrived = true;
            still = true;
            return;
        }
        top = Math.min(byTurn != 0 ? MAX_TURN_SPEED / Math.abs(byTurn) : Float.POSITIVE_INFINITY,
                byTilt != 0 ? MAX_TILT_SPEED / Math.abs(byTilt) : Float.POSITIVE_INFINITY);
        // keep only the part of the old speed that already heads for the new goal: no swinging away first
        speed = Math.min(top, Math.max(0, (wasTurn * byTurn + wasTilt * byTilt) / size));
        progress = 0;
        calm = false;
        arrived = false;
        still = false;
    }

    /** Where the move has the dish at its progress: the tilt kept above its limit, without turning back early. */
    private void place(Shape shape) {
        turn = fromTurn + progress * byTurn;
        float along = fromTilt + progress * byTilt;
        float floor;
        if (!arrived) {
            floor = byTilt >= 0 ? shape.lowestTiltBetween(fromTurn, turn) : shape.lowestTiltBetween(turn, goalTurn);
        } else {
            floor = shape.lowestTilt(turn);
        }
        tilt = Math.min(shape.highestTilt(), Math.max(along, floor));
    }

    /** Where the current mode wants the dish: sets {@link #goalTurn} (any angle) and {@link #goalTilt}. */
    private void goal(Shape shape) {
        switch (mode) {
            case REST -> {
                goalTurn = 0;
                goalTilt = shape.restTilt();
            }
            case UP -> {
                goalTurn = turn;
                goalTilt = shape.highestTilt();
            }
            case AIM -> {
                goalTurn = aimTurn;
                goalTilt = Math.clamp(aimTilt, shape.lowestTilt(aimTurn), shape.highestTilt());
            }
            case SEARCH -> {
                float side = nextRandom() < 0.5F ? 1 : -1;
                goalTurn = turn + side * (30 + nextRandom() * 120);
                float low = shape.lowestTilt(goalTurn);
                goalTilt = low + nextRandom() * (SEARCH_TILT - low);
            }
        }
    }

    private float nextPause() {
        return 0.35F + nextRandom() * 1.2F;
    }

    private float nextRandom() {
        random ^= random << 13;
        random ^= random >>> 7;
        random ^= random << 17;
        return (random >>> 40) / (float) (1L << 24);
    }

    /** An angle brought into -180 to 180. */
    public static float wrap(float degrees) {
        float a = (degrees + 180) % 360;
        return (a < 0 ? a + 360 : a) - 180;
    }

    private static float sq(float v) {
        return v * v;
    }
}
