package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.brain.BrainStatus;
import java.util.List;

/** Which lines a Brain's Jade tooltip shows. Kept apart from Jade so it can be tested. */
final class BrainLines {
    private BrainLines() {}

    enum Line {
        ALONE, MACHINES, FLOORS, STATUS
    }

    /**
     * A brain that can't pay for its tick has no machine limit to speak of yet, so the count stays hidden until it is
     * powered. A resting brain did pay: another brain leads, and the count is that network's.
     */
    static List<Line> shown(boolean alone, BrainStatus status) {
        if (alone) {
            return List.of(Line.ALONE);
        }
        if (status == BrainStatus.NO_POWER) {
            return List.of(Line.FLOORS, Line.STATUS);
        }
        return List.of(Line.MACHINES, Line.FLOORS, Line.STATUS);
    }
}
