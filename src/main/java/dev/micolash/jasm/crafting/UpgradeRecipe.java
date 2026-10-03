package dev.micolash.jasm.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferMerge;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
 * Crafts the next tier of an item from the tier below, like a normal shaped recipe, but the lower tier's contents
 * carry over. The ingredients matching {@code source} are the lower-tier items.
 * <ul>
 * <li>Decks, Archives, generators: the result takes over the source item's data (inserted wafers, Archive identity
 * and links, stored charge).
 * <li>Wafers ({@code merge_wafers}): the result notes every source wafer; their items move over when a player first
 * holds it (see {@link WaferMerge}).
 * </ul>
 */
public class UpgradeRecipe extends NormalCraftingRecipe {
    public static final MapCodec<UpgradeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(r -> r.commonInfo),
            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(r -> r.bookInfo),
            ShapedRecipePattern.MAP_CODEC.forGetter(r -> r.pattern),
            Ingredient.CODEC.fieldOf("source").forGetter(r -> r.source),
            Codec.BOOL.optionalFieldOf("merge_wafers", false).forGetter(r -> r.mergeWafers),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(r -> r.result))
            .apply(i, UpgradeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpgradeRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, r -> r.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, r -> r.bookInfo,
            ShapedRecipePattern.STREAM_CODEC, r -> r.pattern,
            Ingredient.CONTENTS_STREAM_CODEC, r -> r.source,
            ByteBufCodecs.BOOL, r -> r.mergeWafers,
            ItemStackTemplate.STREAM_CODEC, r -> r.result,
            UpgradeRecipe::new);
    public static final RecipeSerializer<UpgradeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final ShapedRecipePattern pattern;
    private final Ingredient source;
    private final boolean mergeWafers;
    private final ItemStackTemplate result;

    public UpgradeRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, ShapedRecipePattern pattern, Ingredient source,
            boolean mergeWafers, ItemStackTemplate result) {
        super(commonInfo, bookInfo);
        this.pattern = pattern;
        this.source = source;
        this.mergeWafers = mergeWafers;
        this.result = result;
    }

    @Override
    public RecipeSerializer<UpgradeRecipe> getSerializer() {
        return JasmRecipes.UPGRADE.get();
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.createFromOptionals(pattern.ingredients());
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return pattern.matches(input);
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        List<ItemStack> sources = input.items().stream().filter(source).toList();
        if (mergeWafers) {
            ItemStack wafer = result.create();
            List<WaferIdentity> merged = WaferMerge.sourcesOf(sources);
            if (!merged.isEmpty()) {
                wafer.set(JasmComponents.WAFER_MERGE.get(), new WaferMerge(merged));
            }
            return wafer;
        }
        return sources.isEmpty() ? result.create() : sources.getFirst().transmuteCopy(result.item().value(), result.count());
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
