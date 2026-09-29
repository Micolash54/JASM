package dev.micolash.jasm.workshop;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmRecipes;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

/**
 * A critter with a full training bar grows up into the next stage. A shaped recipe; the ingredient matching
 * {@code source} is the critter. The result keeps its charge and starts training from zero.
 */
public class EvolveRecipe extends NormalCraftingRecipe {
    public static final MapCodec<EvolveRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Recipe.CommonInfo.MAP_CODEC.forGetter(r -> r.commonInfo),
                    CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(r -> r.bookInfo),
                    ShapedRecipePattern.MAP_CODEC.forGetter(r -> r.pattern),
                    Ingredient.CODEC.fieldOf("source").forGetter(r -> r.source),
                    ItemStackTemplate.CODEC.fieldOf("result").forGetter(r -> r.result))
            .apply(i, EvolveRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, EvolveRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, r -> r.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, r -> r.bookInfo,
            ShapedRecipePattern.STREAM_CODEC, r -> r.pattern,
            Ingredient.CONTENTS_STREAM_CODEC, r -> r.source,
            ItemStackTemplate.STREAM_CODEC, r -> r.result,
            EvolveRecipe::new);
    public static final RecipeSerializer<EvolveRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final ShapedRecipePattern pattern;
    private final Ingredient source;
    private final ItemStackTemplate result;

    public EvolveRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, ShapedRecipePattern pattern, Ingredient source,
            ItemStackTemplate result) {
        super(commonInfo, bookInfo);
        this.pattern = pattern;
        this.source = source;
        this.result = result;
    }

    @Override
    public RecipeSerializer<EvolveRecipe> getSerializer() {
        return JasmRecipes.EVOLVE.get();
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.createFromOptionals(pattern.ingredients());
    }

    /** Only a critter whose training bar is full can evolve. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return pattern.matches(input) && input.items().stream().filter(source).allMatch(EvolveRecipe::fullyTrained);
    }

    private static boolean fullyTrained(ItemStack stack) {
        return stack.getItem() instanceof BitlingItem bitling && Training.full(BitlingItem.trained(stack), bitling.trainingRequired());
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        List<ItemStack> sources = input.items().stream().filter(source).toList();
        if (sources.isEmpty()) {
            return result.create();
        }
        ItemStack grown = sources.getFirst().transmuteCopy(result.item().value(), result.count());
        grown.remove(JasmComponents.TRAINING.get());
        return grown;
    }

    @Override
    public List<RecipeDisplay> display() {
        return List.of(new ShapedCraftingRecipeDisplay(
                pattern.width(),
                pattern.height(),
                pattern.ingredients().stream().map(e -> e.map(Ingredient::display).orElse(SlotDisplay.Empty.INSTANCE)).toList(),
                new SlotDisplay.ItemStackSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
    }
}
