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

    public static final ModConfigSpec.EnumValue<DeckSize> DECK_SIZE = BUILDER
            .comment("Height of the Deck screen's item grid")
            .defineEnum("deckSize", DeckSize.SMALL);

    public static final ModConfigSpec.EnumValue<GridEntries.Sort> DECK_SORT = BUILDER
            .comment("What the Deck screen sorts its grid by")
            .defineEnum("deckSort", GridEntries.Sort.NAME);

    public static final ModConfigSpec.BooleanValue DECK_ASCENDING = BUILDER
            .comment("Sorts the Deck screen's grid from smallest to largest (or A to Z)")
            .define("deckAscending", true);

    public static final ModConfigSpec.BooleanValue DECK_SEARCH_SYNC = BUILDER
            .comment("With JEI installed, the Deck's search and JEI's search show the same text")
            .define("deckSearchSync", true);

    public static final ModConfigSpec.BooleanValue BAY_FILTER_COLLAPSED = BUILDER
            .comment("Folds the filter away on bay panels")
            .define("bayFilterCollapsed", false);

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

    public static GridEntries.Sort deckSort() {
        return SPEC.isLoaded() ? DECK_SORT.get() : GridEntries.Sort.NAME;
    }

    public static void setDeckSort(GridEntries.Sort sort) {
        DECK_SORT.set(sort);
        SPEC.save();
    }

    public static boolean deckAscending() {
        return !SPEC.isLoaded() || DECK_ASCENDING.get();
    }

    public static void setDeckAscending(boolean ascending) {
        DECK_ASCENDING.set(ascending);
        SPEC.save();
    }

    /** Before the config has loaded, the two searches are kept together. */
    public static boolean deckSearchSync() {
        return !SPEC.isLoaded() || DECK_SEARCH_SYNC.get();
    }

    public static void setDeckSearchSync(boolean sync) {
        DECK_SEARCH_SYNC.set(sync);
        SPEC.save();
    }

    public static boolean bayFilterCollapsed() {
        return SPEC.isLoaded() && BAY_FILTER_COLLAPSED.get();
    }

    public static void setBayFilterCollapsed(boolean collapsed) {
        BAY_FILTER_COLLAPSED.set(collapsed);
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
