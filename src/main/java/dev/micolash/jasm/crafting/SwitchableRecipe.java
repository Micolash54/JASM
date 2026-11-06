package dev.micolash.jasm.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.registry.JasmRecipes;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * A crafting recipe that only works while its feature is on in this world. It wraps any other crafting recipe and
 * otherwise behaves exactly like it. While off it matches nothing, shows nothing and stays out of the recipe book.
 */
public final class SwitchableRecipe implements CraftingRecipe {
    private static final Codec<CraftingRecipe> CRAFTING = Recipe.CODEC.comapFlatMap(
            recipe -> recipe instanceof CraftingRecipe crafting ? DataResult.success(crafting)
                    : DataResult.error(() -> "Not a crafting recipe: " + recipe),
            crafting -> crafting);
    public static final MapCodec<SwitchableRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Feature.CODEC.fieldOf("feature").forGetter(SwitchableRecipe::feature),
            CRAFTING.fieldOf("recipe").forGetter(SwitchableRecipe::inner))
            .apply(i, SwitchableRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchableRecipe> STREAM_CODEC = StreamCodec.composite(
            Feature.STREAM_CODEC, SwitchableRecipe::feature,
            Recipe.STREAM_CODEC.map(recipe -> (CraftingRecipe) recipe, crafting -> crafting), SwitchableRecipe::inner,
            SwitchableRecipe::new);
    public static final RecipeSerializer<SwitchableRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final Feature feature;
    private final CraftingRecipe inner;

    public SwitchableRecipe(Feature feature, CraftingRecipe inner) {
        this.feature = feature;
        this.inner = inner;
    }

    public Feature feature() {
        return feature;
    }

    public CraftingRecipe inner() {
        return inner;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return feature.on() && inner.matches(input, level);
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

    /** The real ingredients even while off: the game would drop a recipe it can't place, with a warning at every load. */
    @Override
    public PlacementInfo placementInfo() {
        return inner.placementInfo();
    }

    @Override
    public List<RecipeDisplay> display() {
        return feature.on() ? inner.display() : List.of();
    }

    @Override
    public boolean isSpecial() {
        return inner.isSpecial();
    }

    /**
     * The game builds the recipe book's lists before it reads the world's settings, so they start out following each
     * switch's default. Once the settings are in, and before anyone joins, this builds them again if a switched recipe
     * is listed the wrong way. A world on the defaults costs one look per switched recipe.
     */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        RecipeManager recipes = server.getRecipeManager();
        for (RecipeHolder<CraftingRecipe> holder : recipes.recipeMap().byType(RecipeType.CRAFTING)) {
            if (holder.value() instanceof SwitchableRecipe recipe && recipe.feature.on() != listed(recipes, holder.id())) {
                recipes.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
                return;
            }
        }
    }

    private static boolean listed(RecipeManager recipes, ResourceKey<Recipe<?>> id) {
        boolean[] listed = {false};
        recipes.listDisplaysForRecipe(id, entry -> listed[0] = true);
        return listed[0];
    }

    @Override
    public RecipeSerializer<SwitchableRecipe> getSerializer() {
        return JasmRecipes.SWITCHABLE.get();
    }
}
