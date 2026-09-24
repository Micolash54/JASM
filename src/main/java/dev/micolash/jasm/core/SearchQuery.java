package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Deck search box. Words starting with {@code @} match the start of the item's mod id ({@code @mine} matches
 * minecraft); every other word must appear in the item's name. Case never matters. {@code "@minecraft oak"} finds
 * vanilla items with "oak" in their name.
 */
public record SearchQuery(List<String> modPrefixes, List<String> words) {
    public static final SearchQuery ALL = new SearchQuery(List.of(), List.of());

    public static SearchQuery parse(String text) {
        List<String> mods = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("@")) {
                if (token.length() > 1) {
                    mods.add(token.substring(1));
                }
            } else {
                words.add(token);
            }
        }
        return new SearchQuery(List.copyOf(mods), List.copyOf(words));
    }

    /** @param modId the item's namespace, e.g. {@code minecraft} */
    public boolean matches(String name, String modId) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerMod = modId.toLowerCase(Locale.ROOT);
        for (String mod : modPrefixes) {
            if (!lowerMod.startsWith(mod)) {
                return false;
            }
        }
        for (String word : words) {
            if (!lowerName.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
