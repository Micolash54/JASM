package dev.micolash.jasm.core;

import org.jspecify.annotations.Nullable;

/** How grown up a critter is. */
public enum BitlingStage {
    BITLING,
    NIBBLING,
    BYTELING;

    /** The stage it evolves into, or null when fully grown. */
    public @Nullable BitlingStage next() {
        return this == BYTELING ? null : values()[ordinal() + 1];
    }
}
