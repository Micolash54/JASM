package dev.micolash.jasm.brain;

/** What a Network Brain is doing, as its screen tells it. */
public enum BrainStatus {
    /** "Learning": powered, leading, and below its size's top level. */
    LEARNING,
    /** "No power": it couldn't pay for the last tick. */
    NO_POWER,
    /** "Needs a bigger chamber": at the top level its size allows. */
    NEEDS_BIGGER,
    /** "Fully grown": at the highest level there is. */
    FULLY_GROWN,
    /** "Resting": another brain leads this network. */
    RESTING
}
