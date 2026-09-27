package dev.micolash.jasm.autocraft;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import dev.micolash.jasm.registry.JasmComponents;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** What a Filled Recipe Card holds: a crafting recipe, or a recipe for a machine behind an Access Port. */
public sealed interface Card permits RecipeCard, ProcessingCard {
    /** Either kind, each saved in its own shape; crafting cards saved before processing cards existed still load. */
    Codec<Card> CODEC = Codec.either(RecipeCard.CODEC, ProcessingCard.CODEC).xmap(
            either -> either.map(c -> (Card) c, c -> (Card) c),
            card -> card instanceof RecipeCard crafting ? Either.left(crafting) : Either.right((ProcessingCard) card));

    /** What one craft (or one set through a machine) makes. A fresh copy. */
    ItemStack result();

    /** The card on a Filled Recipe Card, of either kind. */
    static @Nullable Card of(ItemStack stack) {
        RecipeCard crafting = stack.get(JasmComponents.RECIPE_CARD.get());
        return crafting != null ? crafting : stack.get(JasmComponents.PROCESSING_CARD.get());
    }

    /** Puts {@code card} on {@code stack}, replacing a card of the other kind. */
    static void set(ItemStack stack, Card card) {
        if (card instanceof RecipeCard crafting) {
            stack.remove(JasmComponents.PROCESSING_CARD.get());
            stack.set(JasmComponents.RECIPE_CARD.get(), crafting);
        } else {
            stack.remove(JasmComponents.RECIPE_CARD.get());
            stack.set(JasmComponents.PROCESSING_CARD.get(), (ProcessingCard) card);
        }
    }
}
