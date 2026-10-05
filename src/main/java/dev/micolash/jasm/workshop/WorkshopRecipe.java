package dev.micolash.jasm.workshop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.registry.JasmRecipes;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Something a critter builds in the Chip Workshop from up to four items in its grid, in any order. Each recipe names
 * the critter it needs: a kind (or any) and the youngest stage that can do it. It takes {@code ticks} and draws
 * {@code energy} FE in total from the critter's battery, a little each tick.
 */
public class WorkshopRecipe implements Recipe<WorkshopInput> {
    private static final Codec<BitlingStage> STAGE_CODEC = Codec.STRING.comapFlatMap(
            name -> Arrays.stream(BitlingStage.values()).filter(s -> lower(s).equals(name)).findFirst()
                    .map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Unknown Bitling stage: " + name)),
            WorkshopRecipe::lower);
    /** "any", or a typed kind. A Basic Bitling has no kind to ask for. */
    private static final Codec<Optional<BitlingKind>> KIND_CODEC = Codec.STRING.comapFlatMap(
            name -> name.equals("any") ? DataResult.success(Optional.<BitlingKind>empty())
                    : Arrays.stream(BitlingKind.values()).filter(k -> k != BitlingKind.BASIC && lower(k).equals(name)).findFirst()
                            .map(k -> DataResult.success(Optional.of(k)))
                            .orElseGet(() -> DataResult.error(() -> "Unknown Bitling kind: " + name)),
            kind -> kind.map(WorkshopRecipe::lower).orElse("any"));

    public static final MapCodec<WorkshopRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(r -> r.commonInfo),
            Ingredient.CODEC.listOf(1, 4).fieldOf("ingredients").forGetter(r -> r.ingredients),
            KIND_CODEC.fieldOf("bitling_kind").forGetter(r -> r.kind),
            STAGE_CODEC.fieldOf("bitling_stage").forGetter(r -> r.stage),
            // A Workshop screen is sent progress in 16 bits, so a recipe can run for 25 minutes at most.
            Codec.intRange(1, 30_000).fieldOf("ticks").forGetter(r -> r.ticks),
            ExtraCodecs.NON_NEGATIVE_INT.fieldOf("energy").forGetter(r -> r.energy),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(r -> r.result))
            .apply(i, WorkshopRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkshopRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, r -> r.commonInfo,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list(4)), r -> r.ingredients,
            ByteBufCodecs.optional(ByteBufCodecs.idMapper(i -> BitlingKind.values()[i], Enum::ordinal)), r -> r.kind,
            ByteBufCodecs.idMapper(i -> BitlingStage.values()[i], Enum::ordinal), r -> r.stage,
            ByteBufCodecs.VAR_INT, r -> r.ticks,
            ByteBufCodecs.VAR_INT, r -> r.energy,
            ItemStackTemplate.STREAM_CODEC, r -> r.result,
            WorkshopRecipe::new);
    public static final RecipeSerializer<WorkshopRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final Recipe.CommonInfo commonInfo;
    private final List<Ingredient> ingredients;
    private final Optional<BitlingKind> kind;
    private final BitlingStage stage;
    private final int ticks;
    private final int energy;
    private final ItemStackTemplate result;

    public WorkshopRecipe(Recipe.CommonInfo commonInfo, List<Ingredient> ingredients, Optional<BitlingKind> kind, BitlingStage stage, int ticks,
            int energy, ItemStackTemplate result) {
        this.commonInfo = commonInfo;
        this.ingredients = List.copyOf(ingredients);
        this.kind = kind;
        this.stage = stage;
        this.ticks = ticks;
        this.energy = energy;
        this.result = result;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The grid slot each ingredient takes, in ingredient order, or null when one is missing. Other items are ignored. */
    public int @Nullable [] assign(WorkshopInput input) {
        int[] slots = new int[ingredients.size()];
        return place(input, 0, slots, 0) ? slots : null;
    }

    // Tries every free slot for each ingredient in turn; with four slots at most this is a handful of checks.
    private boolean place(WorkshopInput input, int next, int[] slots, int taken) {
        if (next == ingredients.size()) {
            return true;
        }
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if ((taken & 1 << slot) == 0 && !stack.isEmpty() && ingredients.get(next).test(stack)) {
                slots[next] = slot;
                if (place(input, next + 1, slots, taken | 1 << slot)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether this critter can make it: the right kind, and grown up at least as far as the recipe asks. */
    public boolean accepts(BitlingItem critter) {
        return kind.map(k -> critter.kind() == k).orElse(true) && critter.stage().ordinal() >= stage.ordinal();
    }

    /** FE taken on the tick that moves progress from {@code progress} to the next step; the steps add up to {@link #energy()}. */
    public long energyAt(int progress) {
        return (long) energy * (progress + 1) / ticks - (long) energy * progress / ticks;
    }

    public List<Ingredient> ingredients() {
        return ingredients;
    }

    public Optional<BitlingKind> kind() {
        return kind;
    }

    public BitlingStage stage() {
        return stage;
    }

    public int ticks() {
        return ticks;
    }

    public int energy() {
        return energy;
    }

    public ItemStack result() {
        return result.create();
    }

    @Override
    public boolean matches(WorkshopInput input, Level level) {
        return assign(input) != null;
    }

    @Override
    public ItemStack assemble(WorkshopInput input) {
        return result.create();
    }

    // Not a recipe book recipe.
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public boolean showNotification() {
        return commonInfo.showNotification();
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<WorkshopRecipe> getSerializer() {
        return JasmRecipes.WORKSHOP.get();
    }

    @Override
    public RecipeType<WorkshopRecipe> getType() {
        return JasmRecipes.WORKSHOP_TYPE.get();
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
