package dev.micolash.jasm.client;

import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.workshop.WorkshopNeed;
import dev.micolash.jasm.workshop.WorkshopRecipe;
import guideme.color.SymbolicColor;
import guideme.compiler.tags.RecipeTypeMappingSupplier;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytSlotGrid;
import guideme.document.block.recipes.LytStandardRecipeBox;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws every recipe kind in the guide: crafting, the furnaces, smithing, stonecutting, campfire, quenching and the Workshop.
 * GuideME's own boxes are switched off so a shapeless recipe still shows a full 3x3 grid.
 */
final class GuideRecipes implements RecipeTypeMappingSupplier {
    @Override
    public void collect(RecipeTypeMappings mappings) {
        mappings.addStreamFactory(RecipeType.CRAFTING, GuideRecipes::crafting);
        mappings.add(RecipeType.SMELTING, holder -> cooking(holder, Blocks.FURNACE));
        mappings.add(RecipeType.BLASTING, holder -> cooking(holder, Blocks.BLAST_FURNACE));
        mappings.add(RecipeType.SMOKING, holder -> cooking(holder, Blocks.SMOKER));
        mappings.addStreamFactory(RecipeType.SMITHING, GuideRecipes::smithing);
        mappings.add(RecipeType.STONECUTTING, GuideRecipes::stonecutting);
        mappings.add(RecipeType.CAMPFIRE_COOKING, GuideRecipes::campfire);
        mappings.add(JasmRecipes.QUENCHING_TYPE.get(), GuideRecipes::quenching);
        mappings.add(JasmRecipes.WORKSHOP_TYPE.get(), GuideRecipes::workshop);
    }

    private static Stream<LytStandardRecipeBox<CraftingRecipe>> crafting(RecipeHolder<CraftingRecipe> holder) {
        return holder.value().display().stream().map(display -> craftingBox(holder, display));
    }

    /** One crafting recipe in a 3x3 grid. Shaped ones keep their pattern in the top left corner, shapeless ones fill it row by row. */
    private static LytStandardRecipeBox<CraftingRecipe> craftingBox(RecipeHolder<CraftingRecipe> holder, RecipeDisplay display) {
        LytSlotGrid grid = new LytSlotGrid(3, 3);
        String title = "guideme.guidebook.Crafting";
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            for (int i = 0; i < shaped.ingredients().size(); i++) {
                grid.setDisplay(i % shaped.width(), i / shaped.width(), shaped.ingredients().get(i));
            }
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            title = "guideme.guidebook.ShapelessCrafting";
            for (int i = 0; i < Math.min(9, shapeless.ingredients().size()); i++) {
                grid.setDisplay(i % 3, i / 3, shapeless.ingredients().get(i));
            }
        }
        return LytStandardRecipeBox.builder()
                .icon(Blocks.CRAFTING_TABLE)
                .title(Component.translatable(title).getString())
                .input(grid)
                .outputFromResultOf(display)
                .build(holder);
    }

    private static <T extends AbstractCookingRecipe> LytStandardRecipeBox<T> cooking(RecipeHolder<T> holder, Block block) {
        return LytStandardRecipeBox.builder()
                .icon(block)
                .title(block.getName().getString())
                .input(holder.value().input())
                .outputFromResultOf(holder)
                .build(holder);
    }

    private static Stream<LytStandardRecipeBox<SmithingRecipe>> smithing(RecipeHolder<SmithingRecipe> holder) {
        return holder.value().display().stream()
                .filter(SmithingRecipeDisplay.class::isInstance)
                .map(SmithingRecipeDisplay.class::cast)
                .map(display -> LytStandardRecipeBox.builder()
                        .icon(Blocks.SMITHING_TABLE)
                        .title(Blocks.SMITHING_TABLE.getName().getString())
                        .input(LytSlotGrid.rowFromDisplays(List.of(display.template(), display.base(), display.addition()), true))
                        .outputFromResultOf(display)
                        .build(holder));
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
        String seconds = String.format(Locale.ROOT, "%.1f", Tuning.QUENCH_TICKS / 20.0).replace(".0", "");
        return LytStandardRecipeBox.builder()
                .icon(Items.WATER_BUCKET)
                .title(Component.translatable("jei.jasm.category.quenching").getString())
                .input(recipe.input())
                .output(ItemStackTemplate.fromNonEmptyStack(recipe.result(1)))
                .addBottom(note(Component.translatable("jei.jasm.quenching.hint", seconds).getString()))
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
                .addBottom(note(need + " " + cost))
                .build(holder);
    }

    /** A line under a recipe, in the same dark grey as the box's title so it reads on the light panel. */
    private static LytParagraph note(String text) {
        LytParagraph paragraph = LytParagraph.of(text);
        paragraph.modifyStyle(style -> style.color(SymbolicColor.CRAFTING_RECIPE_TYPE));
        return paragraph;
    }
}
