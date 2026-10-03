package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.registry.JasmItems;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** One page per fuel: how much power it makes, and for how long, in each Combustion Generator. */
public class GeneratorFuelCategory extends AbstractRecipeCategory<GeneratorFuelCategory.Fuel> {
    public static final IRecipeType<Fuel> TYPE = IRecipeType.create(Jasm.id("combustion_generator"), Fuel.class);
    private static final int TEXT = 0xFF404040;
    private static final int LINE_HEIGHT = 10;

    /** Items that burn for {@code furnaceTicks} in a furnace. */
    public record Fuel(List<ItemStack> items, int furnaceTicks) {}

    public GeneratorFuelCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("jei.jasm.combustion_generator"),
                gui.createDrawableItemLike(JasmItems.generator(GeneratorTier.BASIC)), 150, GeneratorTier.values().length * LINE_HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Fuel fuel, IFocusGroup focuses) {
        builder.addInputSlot(1, (getHeight() - 16) / 2).setStandardSlotBackground().addItemStacks(fuel.items());
    }

    @Override
    public void draw(Fuel fuel, IRecipeSlotsView slots, GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        GeneratorTier[] tiers = GeneratorTier.values();
        for (int i = 0; i < tiers.length; i++) {
            GeneratorTier tier = tiers[i];
            int ticks = tier.burnTicks(fuel.furnaceTicks());
            Component line = Component.translatable("jei.jasm.combustion_generator.line",
                    Component.translatable("jei.jasm.combustion_generator." + tier.name().toLowerCase(java.util.Locale.ROOT)),
                    String.format("%,d", (long) ticks * tier.fePerTick()), seconds(ticks));
            graphics.text(font, line, 24, i * LINE_HEIGHT + 1, TEXT, false);
        }
    }

    /** Whole seconds where it divides evenly, otherwise one decimal. */
    private static String seconds(int ticks) {
        return ticks % 20 == 0 ? String.valueOf(ticks / 20) : String.format("%.1f", ticks / 20.0);
    }
}
