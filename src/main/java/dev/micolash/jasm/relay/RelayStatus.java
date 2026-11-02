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
    /** Both ends are there, but this end's battery is flat, and a link only starts with both able to run. */
    NEEDS_POWER,
    /** Both ends are there and this one can run, but the far end's battery is flat. */
    FAR_BATTERY_FLAT,
    /** Linked, with one side unable to run. */
    LINKED_ONE_SIDE,
    /** Linked, with both sides able to run. */
    LINKED
}
