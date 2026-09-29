package dev.micolash.jasm.crystal;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.registry.JasmRecipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;

/** An item lying in water for a moment turns into {@code result}, one for one. See {@link Quenching}. */
public class QuenchingRecipe extends SingleItemRecipe {
    public static final MapCodec<QuenchingRecipe> MAP_CODEC = simpleMapCodec(QuenchingRecipe::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, QuenchingRecipe> STREAM_CODEC = simpleStreamCodec(QuenchingRecipe::new);
    public static final RecipeSerializer<QuenchingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public QuenchingRecipe(Recipe.CommonInfo commonInfo, Ingredient ingredient, ItemStackTemplate result) {
        super(commonInfo, ingredient, result);
    }

    /** The finished item, for one input item. */
    public ItemStack result(int count) {
        return result().create().copyWithCount(count);
    }

    @Override
    public RecipeType<QuenchingRecipe> getType() {
        return JasmRecipes.QUENCHING_TYPE.get();
    }

    @Override
    public RecipeSerializer<QuenchingRecipe> getSerializer() {
        return JasmRecipes.QUENCHING.get();
    }

    @Override
    public String group() {
        return "";
    }

    // Not a recipe book recipe.
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CRAFTING_MISC;
    }
}
