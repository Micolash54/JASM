package dev.micolash.jasm.client.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * The text of a JEI info page. A page is one language line; its paragraphs are split in the file by a backslash and an n,
 * twice. They are split here, not by JEI, so the page looks the same on every JEI: each paragraph is its own line of text
 * with an empty line between them.
 */
final class PageText {
    /** Written {@code \\n\\n} in the language file: a backslash, an n, a backslash, an n. */
    static final String BREAK = "\\n\\n";

    private static final Pattern BREAK_PATTERN = Pattern.compile(Pattern.quote(BREAK));

    /** The paragraphs of a page's text, in order. A text without a break is one paragraph. */
    static List<String> paragraphs(String text) {
        return List.of(BREAK_PATTERN.split(text, -1));
    }

    /** The lines to hand JEI for a page: one per paragraph, an empty one between paragraphs. Numbers are filled in first. */
    static Component[] lines(Component translated) {
        List<String> paragraphs = paragraphs(translated.getString());
        List<Component> lines = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (!lines.isEmpty()) {
                lines.add(Component.empty());
            }
            lines.add(Component.literal(paragraph));
        }
        return lines.toArray(new Component[0]);
    }

    private PageText() {}
}
