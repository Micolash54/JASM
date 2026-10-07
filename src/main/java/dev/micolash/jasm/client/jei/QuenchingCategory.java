package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import java.util.List;
import java.util.Locale;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** An Unquenched chip dropped in water becomes a finished chip. */
public class QuenchingCategory extends AbstractRecipeCategory<QuenchingCategory.Quench> {
    public static final IRecipeType<Quench> TYPE = IRecipeType.create(Jasm.id("quenching"), Quench.class);

    public record Quench(List<ItemStack> inputs, ItemStack result) {
        static Quench of(QuenchingRecipe recipe) {
            return new Quench(recipe.input().items().map(ItemStack::new).toList(), recipe.result(1));
        }
    }

    public QuenchingCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("jei.jasm.category.quenching"), gui.createDrawableItemLike(Items.WATER_BUCKET), 110, 40);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Quench quench, IFocusGroup focuses) {
        builder.addInputSlot(12, 4).setStandardSlotBackground().addItemStacks(quench.inputs());
        builder.addOutputSlot(80, 4).setOutputSlotBackground().add(quench.result());
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, Quench quench, IFocusGroup focuses) {
        builder.addRecipeArrowWidget().setPosition(44, 4);
        String seconds = String.format(Locale.ROOT, "%.1f", Tuning.QUENCH_TICKS / 20.0).replace(".0", "");
        builder.addText(Component.translatable("jei.jasm.quenching.hint", seconds), getWidth(), 12).setPosition(0, 28);
    }
}
