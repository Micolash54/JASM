package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crafting.UpgradeRecipe;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Jasm.MODID);

    /** Tier upgrades that keep the lower tier's contents. */
    public static final Supplier<RecipeSerializer<UpgradeRecipe>> UPGRADE = SERIALIZERS.register("upgrade", () -> UpgradeRecipe.SERIALIZER);

    private JasmRecipes() {}
}
