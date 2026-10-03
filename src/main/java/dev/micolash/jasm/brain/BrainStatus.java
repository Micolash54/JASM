package dev.micolash.jasm.brain;

/** What a Network Brain is doing, as its screen tells it. A brain in a tower shows its tower's. */
public enum BrainStatus {
    /** "Working": powered and leading its network. */
    WORKING,
    /** "No power": it couldn't pay for the last tick. */
    NO_POWER,
    /** "Resting": another brain leads this network. */
    RESTING
}
