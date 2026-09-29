package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import dev.micolash.jasm.workshop.EvolveRecipe;
import dev.micolash.jasm.autocraft.WipeCardRecipe;
import dev.micolash.jasm.crafting.UpgradeRecipe;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Jasm.MODID);

    /** Tier upgrades that keep the lower tier's contents. */
    public static final Supplier<RecipeSerializer<UpgradeRecipe>> UPGRADE = SERIALIZERS.register("upgrade", () -> UpgradeRecipe.SERIALIZER);

    /** A Filled Recipe Card alone in the grid gives an Empty one back. */
    public static final Supplier<RecipeSerializer<WipeCardRecipe>> WIPE_CARD = SERIALIZERS.register("wipe_card", () -> WipeCardRecipe.SERIALIZER);

    /** A fully trained critter grows up. */
    public static final Supplier<RecipeSerializer<EvolveRecipe>> EVOLVE = SERIALIZERS.register("evolve", () -> EvolveRecipe.SERIALIZER);

    /** Hot chips cooled in water. */
    public static final Supplier<RecipeSerializer<QuenchingRecipe>> QUENCHING = SERIALIZERS.register("quenching", () -> QuenchingRecipe.SERIALIZER);

    public static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, Jasm.MODID);

    public static final Supplier<RecipeType<QuenchingRecipe>> QUENCHING_TYPE = TYPES.register("quenching",
            () -> RecipeType.simple(Jasm.id("quenching")));

    private JasmRecipes() {}
}
