package dev.micolash.jasm.bay;

import java.util.Locale;

/** What a bay's panel says. Those that no block update announces are looked at again every half second. */
public enum BayStatus {
    WORKING(false),
    SLEEPING(false),
    NO_POWER(true),
    GRID_FULL(false),
    TANK_FULL(false),
    NOTHING_TO_PLACE(false),
    BLOCKED(false),
    PAUSED(false),
    TOO_MANY_ITEMS(true),
    NOT_ALLOWED(true),
    FRONT_NOT_LOADED(true),
    /** Someone stands in the space a block would go; nothing announces them leaving, so it looks again. */
    OBSTRUCTED(true);

    private final boolean recheck;

    BayStatus(boolean recheck) {
        this.recheck = recheck;
    }

    public boolean recheck() {
        return recheck;
    }

    public String key() {
        return "screen.jasm.bay.status." + name().toLowerCase(Locale.ROOT);
    }

    /** A word or two that fits beside the tank; {@link #key()} is the full line. */
    public String shortKey() {
        return "screen.jasm.bay.status_short." + name().toLowerCase(Locale.ROOT);
    }

    public static BayStatus byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : SLEEPING;
    }
}
