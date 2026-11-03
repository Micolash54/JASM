package dev.micolash.jasm.config;

import dev.micolash.jasm.core.GridEntries;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/** Client config: each player's own screen choices. */
public final class JasmClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * How tall the Deck screen's grid is, and the Rack Controller's rack list; every size above the smallest grows with the
     * game window.
     */
    public enum DeckSize implements TranslatableEnum {
        SMALL,
        MEDIUM,
        TALL,
        FULL;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("jasm.configuration.deckSize." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** What the Deck's Network tab sorts its groups and rows by. */
    public enum NetworkSort implements TranslatableEnum {
        POWER,
        NAME,
        TYPE;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("jasm.configuration.networkSort." + name().toLowerCase(Locale.ROOT));
        }
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

    public static final ModConfigSpec.BooleanValue NETWORK_GROUPED = BUILDER
            .comment("Groups the Deck's Network tab by category")
            .define("networkGrouped", true);

    public static final ModConfigSpec.EnumValue<NetworkSort> NETWORK_SORT = BUILDER
            .comment("What the Deck's Network tab sorts by")
            .defineEnum("networkSort", NetworkSort.POWER);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> NETWORK_FOLDED = BUILDER
            .comment("The Network tab categories folded shut")
            .defineListAllowEmpty("networkFolded", List.of(), () -> "power", value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue BAY_FILTER_COLLAPSED = BUILDER
            .comment("Folds the filter away on bay panels")
            .define("bayFilterCollapsed", false);

    public static final ModConfigSpec.BooleanValue PORT_RECIPES_FOLDED = BUILDER
            .comment("Folds the recipe list away on Access Port screens")
            .define("portRecipesFolded", false);

    public static final ModConfigSpec.BooleanValue ENCODER_PORTS_FOLDED = BUILDER
            .comment("Folds the port list away on Advanced Encoder screens")
            .define("encoderPortsFolded", false);

    public static final ModConfigSpec.EnumValue<DeckSize> RACK_CONTROLLER_SIZE = BUILDER
            .comment("Height of the Rack Controller's rack list")
            .defineEnum("rackControllerSize", DeckSize.SMALL);

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

    /** Before the config has loaded, the smallest size. */
    public static DeckSize rackControllerSize() {
        return SPEC.isLoaded() ? RACK_CONTROLLER_SIZE.get() : DeckSize.SMALL;
    }

    public static void setRackControllerSize(DeckSize size) {
        RACK_CONTROLLER_SIZE.set(size);
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

    /** Before the config has loaded, the tab is grouped. */
    public static boolean networkGrouped() {
        return !SPEC.isLoaded() || NETWORK_GROUPED.get();
    }

    public static void setNetworkGrouped(boolean grouped) {
        NETWORK_GROUPED.set(grouped);
        SPEC.save();
    }

    public static NetworkSort networkSort() {
        return SPEC.isLoaded() ? NETWORK_SORT.get() : NetworkSort.POWER;
    }

    public static void setNetworkSort(NetworkSort sort) {
        NETWORK_SORT.set(sort);
        SPEC.save();
    }

    public static List<? extends String> networkFolded() {
        return SPEC.isLoaded() ? NETWORK_FOLDED.get() : List.of();
    }

    public static void setNetworkFolded(List<String> folded) {
        NETWORK_FOLDED.set(List.copyOf(folded));
        SPEC.save();
    }

    public static boolean bayFilterCollapsed() {
        return SPEC.isLoaded() && BAY_FILTER_COLLAPSED.get();
    }

    public static void setBayFilterCollapsed(boolean collapsed) {
        BAY_FILTER_COLLAPSED.set(collapsed);
        SPEC.save();
    }

    public static boolean portRecipesFolded() {
        return SPEC.isLoaded() && PORT_RECIPES_FOLDED.get();
    }

    public static void setPortRecipesFolded(boolean folded) {
        PORT_RECIPES_FOLDED.set(folded);
        SPEC.save();
    }

    public static boolean encoderPortsFolded() {
        return SPEC.isLoaded() && ENCODER_PORTS_FOLDED.get();
    }

    public static void setEncoderPortsFolded(boolean folded) {
        ENCODER_PORTS_FOLDED.set(folded);
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
