package dev.micolash.jasm.config;

import dev.micolash.jasm.core.GridEntries;
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

    public static final ModConfigSpec.EnumValue<GridEntries.Kinds> DECK_KINDS = BUILDER
            .comment("What the Deck screen's grid lists: items and fluids, items only, or fluids only")
            .defineEnum("deckKinds", GridEntries.Kinds.ALL);

    public static final ModConfigSpec.EnumValue<DeckSize> DECK_SIZE = BUILDER
            .comment("Height of the Deck screen's item grid")
            .defineEnum("deckSize", DeckSize.SMALL);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Before the config has loaded, the smallest size. */
    public static DeckSize deckSize() {
        return SPEC.isLoaded() ? DECK_SIZE.get() : DeckSize.SMALL;
    }

    /** Before the config has loaded, everything. */
    public static GridEntries.Kinds deckKinds() {
        return SPEC.isLoaded() ? DECK_KINDS.get() : GridEntries.Kinds.ALL;
    }

    public static void setDeckKinds(GridEntries.Kinds kinds) {
        DECK_KINDS.set(kinds);
        SPEC.save();
    }

    public static void setDeckSize(DeckSize size) {
        DECK_SIZE.set(size);
        SPEC.save();
    }

    private JasmClientConfig() {}
}
