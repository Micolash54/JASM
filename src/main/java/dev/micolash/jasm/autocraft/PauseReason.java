package dev.micolash.jasm.autocraft;

import java.util.Locale;

/**
 * Why a crafting job is waiting. The position in this list is the number sent to screens, so new reasons go at the
 * end.
 */
public enum PauseReason {
    NONE,
    NO_POWER,
    NO_CARD,
    WAITING_PLAYER,
    WAITING_SPACE,
    NO_NETWORK,
    MACHINE_BUSY,
    NO_MACHINE,
    DIMENSION_UPGRADE,
    DECK_CHARGE,
    NETWORK_FULL;

    private static final PauseReason[] ALL = values();

    /** The text key shown for this reason. */
    public String key() {
        return "screen.jasm.server.pause." + name().toLowerCase(Locale.ROOT);
    }

    /** The number sent to screens. */
    public int code() {
        return ordinal();
    }

    /** The reason for a number from {@link #code()}; anything unknown reads as {@link #NONE}. */
    public static PauseReason of(int code) {
        return code > 0 && code < ALL.length ? ALL[code] : NONE;
    }
}
