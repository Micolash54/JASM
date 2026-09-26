package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmRecipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** A Filled Recipe Card alone in a crafting grid turns back into an Empty one. */
public class WipeCardRecipe extends CustomRecipe {
    public static final WipeCardRecipe INSTANCE = new WipeCardRecipe();
    public static final RecipeSerializer<WipeCardRecipe> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE),
            StreamCodec.<RegistryFriendlyByteBuf, WipeCardRecipe>unit(INSTANCE));

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return input.ingredientCount() == 1 && input.items().stream().anyMatch(stack -> stack.is(JasmItems.FILLED_RECIPE_CARD.get()));
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return new ItemStack(JasmItems.RECIPE_CARD.get());
    }

    @Override
    public RecipeSerializer<WipeCardRecipe> getSerializer() {
        return JasmRecipes.WIPE_CARD.get();
    }
}
