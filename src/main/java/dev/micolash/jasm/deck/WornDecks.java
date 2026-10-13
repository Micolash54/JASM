package dev.micolash.jasm.deck;

import java.util.function.Consumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Deck slot a player wears, when Curios is installed. Without Curios there are no worn Decks and nothing here
 * does anything. The Curios side plugs itself in at startup; nothing in this class touches Curios itself.
 */
public final class WornDecks {
    /** The worn Deck slots of one player. */
    public interface Slots {
        int size(Player player);

        ItemStack get(Player player, int index);

        void set(Player player, int index, ItemStack stack);
    }

    private static @Nullable Slots slots;

    private WornDecks() {}

    /** Set once at startup when Curios is installed (or by a test standing in for it); null takes it away again. */
    public static void install(@Nullable Slots worn) {
        slots = worn;
    }

    public static @Nullable Slots installed() {
        return slots;
    }

    /** Whether players have a Deck slot at all. */
    public static boolean available() {
        return slots != null;
    }

    public static int size(Player player) {
        Slots worn = slots;
        return worn == null ? 0 : worn.size(player);
    }

    public static ItemStack get(Player player, int index) {
        Slots worn = slots;
        return worn == null || index < 0 || index >= worn.size(player) ? ItemStack.EMPTY : worn.get(player, index);
    }

    public static void set(Player player, int index, ItemStack stack) {
        Slots worn = slots;
        if (worn != null && index >= 0 && index < worn.size(player)) {
            worn.set(player, index, stack);
        }
    }

    /** Every Deck the player wears. */
    public static void forEach(Player player, Consumer<ItemStack> action) {
        Slots worn = slots;
        if (worn == null) {
            return;
        }
        for (int i = 0; i < worn.size(player); i++) {
            ItemStack stack = worn.get(player, i);
            if (DeckItem.isDeck(stack)) {
                action.accept(stack);
            }
        }
    }

    /** The first worn slot holding a Deck, or -1. */
    public static int firstDeck(Player player) {
        Slots worn = slots;
        if (worn == null) {
            return -1;
        }
        for (int i = 0; i < worn.size(player); i++) {
            if (DeckItem.isDeck(worn.get(player, i))) {
                return i;
            }
        }
        return -1;
    }
}
