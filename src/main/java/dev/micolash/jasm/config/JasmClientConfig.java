package dev.micolash.jasm.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config: each player's own screen choices. */
public final class JasmClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** How tall the Deck screen's grid is; every size above the smallest grows with the game window. */
    public enum DeckSize {
        SMALL,
        MEDIUM,
        TALL,
        FULL
    }

    static {
        BUILDER.push("screens");
    }

    public static final ModConfigSpec.EnumValue<DeckSize> DECK_SIZE = BUILDER
            .comment("Height of the Deck screen's item grid")
            .defineEnum("deckSize", DeckSize.SMALL);

    static {
        BUILDER.pop().push("sounds");
    }

    public static final ModConfigSpec.BooleanValue BAY_LASER_MUTED = BUILDER
            .comment("Silences every Demolition Bay's laser")
            .define("bayLaserMuted", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Before the config has loaded, the smallest size. */
    public static DeckSize deckSize() {
        return SPEC.isLoaded() ? DECK_SIZE.get() : DeckSize.SMALL;
    }

    public static void setDeckSize(DeckSize size) {
        DECK_SIZE.set(size);
        SPEC.save();
    }

    /** Before the config has loaded, the laser is heard. */
    public static boolean bayLaserMuted() {
        return SPEC.isLoaded() && BAY_LASER_MUTED.get();
    }

    public static void setBayLaserMuted(boolean muted) {
        BAY_LASER_MUTED.set(muted);
        SPEC.save();
    }

    private JasmClientConfig() {}
}
