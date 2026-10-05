package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.client.ChipWorkshopNeeds;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.workshop.WorkshopNeed;
import dev.micolash.jasm.workshop.WorkshopRecipe;
import java.util.List;
import java.util.Locale;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.item.ItemStack;

/** Things a critter builds in the Chip Workshop's grid, with the critter it needs, the time and the power. */
public class WorkshopRecipeCategory extends AbstractRecipeCategory<WorkshopRecipe> {
    public static final IRecipeType<WorkshopRecipe> TYPE = IRecipeType.create(Jasm.id("workshop_recipe"), WorkshopRecipe.class);

    public WorkshopRecipeCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("jei.jasm.category.workshop_recipes"), gui.createDrawableItemLike(JasmItems.CHIP_WORKSHOP.get()), 150, 58);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, WorkshopRecipe recipe, IFocusGroup focuses) {
        for (int i = 0; i < recipe.ingredients().size(); i++) {
            builder.addInputSlot(1 + i % 2 * 18, 1 + i / 2 * 18).setStandardSlotBackground().addIngredients(recipe.ingredients().get(i));
        }
        builder.addOutputSlot(70, 10).setOutputSlotBackground().add(recipe.result());
        List<BitlingKind> kinds = recipe.kind().map(List::of).orElseGet(() -> List.of(BitlingKind.LOGIC, BitlingKind.MEMORY, BitlingKind.LINK));
        List<ItemStack> critters = kinds.stream().map(kind -> new ItemStack(JasmItems.bitling(kind, recipe.stage()))).toList();
        builder.addSlot(RecipeIngredientRole.RENDER_ONLY, 110, 10).setStandardSlotBackground().addItemStacks(critters);
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, WorkshopRecipe recipe, IFocusGroup focuses) {
        builder.addRecipeArrowWidget().setPosition(42, 10);
        String seconds = String.format(Locale.ROOT, "%.1f", recipe.ticks() / 20.0).replace(".0", "");
        builder.addText(List.<FormattedText>of(
                Component.translatable("screen.jasm.workshop.need", ChipWorkshopNeeds.needed(WorkshopNeed.of(recipe))),
                Component.translatable("jei.jasm.workshop_recipe.cost", seconds, String.format("%,d", recipe.energy()))),
                getWidth(), 20).setPosition(0, 38);
    }
}
