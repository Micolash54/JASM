package dev.micolash.jasm.client;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.workshop.WorkshopNeed;
import dev.micolash.jasm.workshop.WorkshopRecipe;
import guideme.compiler.tags.RecipeTypeMappingSupplier;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytSlotGrid;
import guideme.document.block.recipes.LytStandardRecipeBox;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.Blocks;

/** Draws the recipe kinds the guide doesn't know by itself: stonecutting, campfire, quenching and the Workshop. */
final class GuideRecipes implements RecipeTypeMappingSupplier {
    @Override
    public void collect(RecipeTypeMappings mappings) {
        mappings.add(RecipeType.STONECUTTING, GuideRecipes::stonecutting);
        mappings.add(RecipeType.CAMPFIRE_COOKING, GuideRecipes::campfire);
        mappings.add(JasmRecipes.QUENCHING_TYPE.get(), GuideRecipes::quenching);
        mappings.add(JasmRecipes.WORKSHOP_TYPE.get(), GuideRecipes::workshop);
    }

    private static LytStandardRecipeBox<StonecutterRecipe> stonecutting(RecipeHolder<StonecutterRecipe> holder) {
        return LytStandardRecipeBox.builder()
                .icon(Blocks.STONECUTTER)
                .title(Blocks.STONECUTTER.getName().getString())
                .input(holder.value().input())
                .outputFromResultOf(holder)
                .build(holder);
    }

    private static LytStandardRecipeBox<CampfireCookingRecipe> campfire(RecipeHolder<CampfireCookingRecipe> holder) {
        return LytStandardRecipeBox.builder()
                .icon(Blocks.CAMPFIRE)
                .title(Blocks.CAMPFIRE.getName().getString())
                .input(holder.value().input())
                .outputFromResultOf(holder)
                .build(holder);
    }

    private static LytStandardRecipeBox<QuenchingRecipe> quenching(RecipeHolder<QuenchingRecipe> holder) {
        QuenchingRecipe recipe = holder.value();
        // the guide can open before a world is loaded, and then the config has no values yet
        int ticks = JasmConfig.SPEC.isLoaded() ? JasmConfig.QUENCH_TICKS.getAsInt() : JasmConfig.QUENCH_TICKS.getDefault();
        String seconds = String.format(Locale.ROOT, "%.1f", ticks / 20.0).replace(".0", "");
        return LytStandardRecipeBox.builder()
                .icon(Items.WATER_BUCKET)
                .title(Component.translatable("jei.jasm.category.quenching").getString())
                .input(recipe.input())
                .output(ItemStackTemplate.fromNonEmptyStack(recipe.result(1)))
                .addBottom(LytParagraph.of(Component.translatable("jei.jasm.quenching.hint", seconds).getString()))
                .build(holder);
    }

    private static LytStandardRecipeBox<WorkshopRecipe> workshop(RecipeHolder<WorkshopRecipe> holder) {
        WorkshopRecipe recipe = holder.value();
        String seconds = String.format(Locale.ROOT, "%.1f", recipe.ticks() / 20.0).replace(".0", "");
        String need = Component.translatable("screen.jasm.workshop.need", ChipWorkshopNeeds.needed(WorkshopNeed.of(recipe))).getString();
        String cost = Component.translatable("jei.jasm.workshop_recipe.cost", seconds, String.format("%,d", recipe.energy())).getString();
        return LytStandardRecipeBox.builder()
                .icon(JasmItems.CHIP_WORKSHOP.get().getDefaultInstance())
                .title(JasmItems.CHIP_WORKSHOP.get().getDefaultInstance().getItemName().getString())
                .input(LytSlotGrid.rowFromIngredients(List.copyOf(recipe.ingredients()), true))
                .output(ItemStackTemplate.fromNonEmptyStack(recipe.result()))
                .addBottom(LytParagraph.of(need + " " + cost))
                .build(holder);
    }
}
