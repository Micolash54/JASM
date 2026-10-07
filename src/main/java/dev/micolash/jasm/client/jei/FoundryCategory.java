package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.registry.JasmItems;
import java.util.Locale;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The Crystal Foundry grows a fixed number of Blank Chips from one Crystal Seed. */
public class FoundryCategory extends AbstractRecipeCategory<FoundryCategory.Grow> {
    public static final IRecipeType<Grow> TYPE = IRecipeType.create(Jasm.id("crystal_foundry"), Grow.class);

    /** The one thing the Foundry does, with the numbers from the config. */
    public record Grow(int chips, int ticksEach) {
        static Grow fromConfig() {
            return JasmConfig.SPEC.isLoaded()
                    ? new Grow(JasmConfig.FOUNDRY_CRYSTALS_PER_SEED.getAsInt(), Tuning.FOUNDRY_TICKS_PER_CRYSTAL)
                    : new Grow(JasmConfig.FOUNDRY_CRYSTALS_PER_SEED.getDefault(), Tuning.FOUNDRY_TICKS_PER_CRYSTAL);
        }
    }

    public FoundryCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("block.jasm.crystal_foundry"), gui.createDrawableItemLike(JasmItems.CRYSTAL_FOUNDRY.get()), 120, 40);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Grow grow, IFocusGroup focuses) {
        builder.addInputSlot(12, 4).setStandardSlotBackground().add(new ItemStack(JasmItems.CRYSTAL_SEED.get()));
        builder.addOutputSlot(88, 4).setOutputSlotBackground().add(new ItemStack(JasmItems.BLANK_CHIP.get(), grow.chips()));
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, Grow grow, IFocusGroup focuses) {
        builder.addAnimatedRecipeArrowWidget(grow.ticksEach()).setPosition(46, 4);
        String seconds = String.format(Locale.ROOT, "%.1f", grow.ticksEach() / 20.0).replace(".0", "");
        builder.addText(Component.translatable("jei.jasm.foundry.hint", grow.chips(), seconds), getWidth(), 12).setPosition(0, 28);
    }
}
