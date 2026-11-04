package dev.micolash.jasm.tether;

/** What a Network Tether is doing, as its operator tells it on the screen. */
public enum TetherStatus {
    /** No Nibbling or Byteling sits at the desk: nothing is held. */
    EMPTY,
    /** Leading its network and still taking hold of its chunks. */
    STARTING,
    /** Leading its network and holding every chunk it knows of. */
    WATCHING,
    /** Another Tether on the network keeps watch; this one holds nothing and pays nothing. */
    STANDBY,
    /** The operator can't pay: the chunks stay held for a short while, then are let go. */
    NO_POWER,
    /** The operator couldn't pay and the chunks have been let go. */
    ASLEEP;

    /** How the block looks: the operator at work, dozing, or not there. */
    public Mood mood() {
        return switch (this) {
            case EMPTY -> Mood.EMPTY;
            case STARTING, WATCHING -> Mood.WATCH;
            case STANDBY, NO_POWER, ASLEEP -> Mood.DOZE;
        };
    }

    /** The three looks of the block. */
    public enum Mood {
        WATCH, DOZE, EMPTY
    }
}
