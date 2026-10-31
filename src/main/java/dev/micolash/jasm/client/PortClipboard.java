package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.ProcessingCard;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The settings last copied from an Access Port: its name and the recipes it serves. Kept on this client until the game
 * closes, never saved; the next copy takes its place.
 */
final class PortClipboard {
    /** {@code name} is the port's own name (empty for none); {@code shown} is what the player saw as its title. */
    record Copied(String name, String shown, List<ProcessingCard> recipes) {}

    private static @Nullable Copied copied;

    private PortClipboard() {}

    static @Nullable Copied get() {
        return copied;
    }

    static void set(String name, String shown, List<ProcessingCard> recipes) {
        copied = new Copied(name, shown, List.copyOf(recipes));
    }
}
