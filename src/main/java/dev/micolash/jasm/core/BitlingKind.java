package dev.micolash.jasm.core;

import org.jspecify.annotations.Nullable;

/** What a critter has learned to make: nothing in particular yet, or one chip type. */
public enum BitlingKind {
    BASIC(null),
    LOGIC(ChipType.LOGIC),
    MEMORY(ChipType.MEMORY),
    LINK(ChipType.LINK);

    private final @Nullable ChipType chipType;

    BitlingKind(@Nullable ChipType chipType) {
        this.chipType = chipType;
    }

    /** The chip type it favours; null for a Basic Bitling. */
    public @Nullable ChipType chipType() {
        return chipType;
    }
}
