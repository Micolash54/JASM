package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
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
    NETWORK_FULL,
    NO_MATERIAL,
    NO_ROOM,
    MACHINE_UNLOADED,
    MACHINE_SIDE,
    PORT_OFF,
    MACHINE_IN_USE,
    MACHINE_OFF,
    /** No Access Port with a machine serves a recipe the job still has crafts of. */
    NO_PORT,
    /** A set stopped being waited on: the Access Port it went through is gone. Kept on the job until it ends. */
    PORT_GONE;

    private static final PauseReason[] ALL = values();

    /** Saved by name; anything unknown reads as {@link #NONE}. */
    public static final Codec<PauseReason> CODEC = Codec.STRING.xmap(PauseReason::named, reason -> reason.name().toLowerCase(Locale.ROOT));

    private static PauseReason named(String name) {
        for (PauseReason reason : ALL) {
            if (reason.name().equalsIgnoreCase(name)) return reason;
        }
        return NONE;
    }

    /** The text key shown for this reason. */
    public String key() {
        if (this == MACHINE_BUSY) return "screen.jasm.server.pause.waiting_machine";
        return "screen.jasm.server.pause." + name().toLowerCase(Locale.ROOT);
    }

    /** A refused input is normal waiting; the player can check the machine if it takes too long. */
    public boolean warning() {
        return this == MACHINE_BUSY;
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
