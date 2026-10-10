package dev.micolash.jasm.client;

import org.jspecify.annotations.Nullable;

/** The search bar of an item list mod (JEI) while one is running, so the Deck's search can keep the same text. */
public final class ItemListSearch {
    public interface Source {
        String text();

        void setText(String text);

        /** Whether the player is typing in it. */
        boolean focused();
    }

    // set while JEI's runtime is up, cleared when it goes
    private static @Nullable Source source;

    private ItemListSearch() {}

    public static void setSource(@Nullable Source source) {
        ItemListSearch.source = source;
    }

    public static @Nullable Source source() {
        return source;
    }
}
