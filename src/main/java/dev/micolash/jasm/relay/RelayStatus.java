package dev.micolash.jasm.relay;

/** Where one end of a Relay Chatterbox pair stands. */
public enum RelayStatus {
    /** No frequency set. */
    NO_FREQUENCY,
    /** Two other ends already hold this frequency; this one waits its turn. */
    WAITING,
    /** No other end holds this frequency yet. */
    NO_PARTNER,
    /** The other end isn't loaded, or its dimension is gone. */
    FAR_END_NOT_LOADED,
    /** The ends stand in two dimensions and neither has a Dimension Upgrade. */
    NEEDS_DIMENSION_UPGRADE,
    /** Both ends are there, but both batteries are flat; a link starts once either end can run. */
    NEEDS_POWER,
    /** Linked, with one side unable to run. */
    LINKED_ONE_SIDE,
    /** Linked, with both sides able to run. */
    LINKED;

    /** The light on the block's front console. */
    public Light light() {
        return switch (this) {
            case NO_FREQUENCY -> Light.OFF;
            case LINKED -> Light.GREEN;
            case LINKED_ONE_SIDE -> Light.YELLOW;
            default -> Light.RED;
        };
    }

    /** The front console's status light: off with no frequency, green linked, yellow linked one side, red otherwise. */
    public enum Light {
        OFF, RED, YELLOW, GREEN
    }
}
