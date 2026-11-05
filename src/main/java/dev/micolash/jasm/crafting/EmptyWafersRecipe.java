package dev.micolash.jasm.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferItem;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

/**
 * A crafting recipe that uses up wafers only while they are empty: a wafer still holding anything doesn't match, so
 * its contents are never lost in the grid. It wraps any other crafting recipe and otherwise behaves exactly like it.
 */
public final class EmptyWafersRecipe implements CraftingRecipe {
    private static final Codec<CraftingRecipe> CRAFTING = Recipe.CODEC.comapFlatMap(
            recipe -> recipe instanceof CraftingRecipe crafting ? DataResult.success(crafting)
                    : DataResult.error(() -> "Not a crafting recipe: " + recipe),
            crafting -> crafting);
    public static final MapCodec<EmptyWafersRecipe> MAP_CODEC = CRAFTING.fieldOf("recipe").xmap(EmptyWafersRecipe::new, EmptyWafersRecipe::inner);
    public static final StreamCodec<RegistryFriendlyByteBuf, EmptyWafersRecipe> STREAM_CODEC = Recipe.STREAM_CODEC
            .map(recipe -> new EmptyWafersRecipe((CraftingRecipe) recipe), EmptyWafersRecipe::inner);
    public static final RecipeSerializer<EmptyWafersRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final CraftingRecipe inner;

    public EmptyWafersRecipe(CraftingRecipe inner) {
        this.inner = inner;
    }

    public CraftingRecipe inner() {
        return inner;
    }

    /** Only the server knows what a wafer holds; it decides what the grid gives, so the client goes by the pattern. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!inner.matches(input, level)) return false;
        if (!(level instanceof ServerLevel server)) return true;
        WaferStore store = WaferStore.get(server.getServer());
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof WaferItem && !WaferEligibility.isEmptyWafer(store, stack)) return false;
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return inner.assemble(input);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return inner.getRemainingItems(input);
    }

    @Override
    public boolean showNotification() {
        return inner.showNotification();
    }

    @Override
    public String group() {
        return inner.group();
    }

    @Override
    public CraftingBookCategory category() {
        return inner.category();
    }

    @Override
    public PlacementInfo placementInfo() {
        return inner.placementInfo();
    }

    @Override
    public List<RecipeDisplay> display() {
        return inner.display();
    }

    @Override
    public boolean isSpecial() {
        return inner.isSpecial();
    }

    @Override
    public RecipeSerializer<EmptyWafersRecipe> getSerializer() {
        return JasmRecipes.EMPTY_WAFERS.get();
    }
}
