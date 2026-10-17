package dev.micolash.jasm.core;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The line that names the mod an item comes from, in italic blue like JEI's. Pure, so it can be tested without the game.
 * Nothing in the game adds it to inventory tooltips on its own, but a mod might, so it is only added when no line
 * already says the same name.
 */
public final class ModNameLine {
    private ModNameLine() {}

    /** The mod id part of a registered name; vanilla ids without a colon belong to Minecraft. */
    public static String namespaceOf(String registeredName) {
        int colon = registeredName.indexOf(':');
        return colon < 0 ? "minecraft" : registeredName.substring(0, colon);
    }

    public static Component line(String modName) {
        return Component.literal(modName).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC);
    }

    /**
     * True when a line is nothing but one of these names, whatever its style: another mod already added it. The first
     * line is the item's own name, which may happen to be the mod's name too.
     */
    public static boolean alreadyShown(List<Component> lines, String... names) {
        for (Component existing : lines.stream().skip(1).toList()) {
            String text = existing.getString().strip();
            for (String name : names) {
                if (!name.isBlank() && text.equalsIgnoreCase(name.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Adds the line at {@code index} unless the list already names the mod. */
    public static void add(List<Component> lines, int index, String modName, String namespace) {
        if (!alreadyShown(lines, modName, namespace)) {
            lines.add(Math.min(index, lines.size()), line(modName));
        }
    }
}
