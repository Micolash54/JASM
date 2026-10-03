package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.core.ChipOdds;
import dev.micolash.jasm.core.ChipType;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.item.ItemStack;

/** One page per critter: the chips it makes from Blank Chips in the Chip Workshop, and how likely each is. */
public class WorkshopCategory extends AbstractRecipeCategory<BitlingItem> {
    public static final IRecipeType<BitlingItem> TYPE = IRecipeType.create(Jasm.id("chip_workshop"), BitlingItem.class);
    private static final int TEXT_X = 104;

    public WorkshopCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("jei.jasm.category.workshop"), gui.createDrawableItemLike(JasmItems.CHIP_WORKSHOP.get()), 170, 40);
    }

    private static ChipOdds.Odds odds(BitlingItem critter) {
        return ChipOdds.of(critter.kind(), critter.stage(), false, BitlingItem.balance());
    }

    private static double chance(ChipOdds.Odds odds, ChipType type) {
        return switch (type) {
            case LOGIC -> odds.logic();
            case MEMORY -> odds.memory();
            case LINK -> odds.link();
        };
    }

    private static boolean byteling(BitlingItem critter) {
        return critter.stage() == BitlingStage.BYTELING;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, BitlingItem critter, IFocusGroup focuses) {
        builder.addInputSlot(1, 1).setStandardSlotBackground().add(new ItemStack(critter));
        builder.addInputSlot(1, 21).setStandardSlotBackground().add(new ItemStack(JasmItems.BLANK_CHIP.get()));
        ChipOdds.Odds odds = odds(critter);
        int column = 0;
        for (ChipType type : ChipType.values()) {
            if (chance(odds, type) <= 0) {
                continue;
            }
            int x = 44 + column++ * 18;
            builder.addOutputSlot(x, 1).setOutputSlotBackground().add(new ItemStack(JasmItems.chip(type, false)));
            if (odds.advanced() > 0 || byteling(critter)) {
                builder.addOutputSlot(x, 21).setOutputSlotBackground().add(new ItemStack(JasmItems.chip(type, true)));
            }
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, BitlingItem critter, IFocusGroup focuses) {
        builder.addRecipeArrowWidget().setPosition(20, 11);
        ChipOdds.Odds odds = odds(critter);
        List<FormattedText> lines = new ArrayList<>();
        for (ChipType type : ChipType.values()) {
            double chance = chance(odds, type);
            if (chance > 0) {
                lines.add(Component.translatable("jei.jasm.workshop.type",
                        Component.translatable("jei.jasm.workshop.chip." + type.name().toLowerCase(Locale.ROOT)), percent(chance)));
            }
        }
        lines.add(byteling(critter) ? Component.translatable("jei.jasm.workshop.advanced_toggle")
                : Component.translatable("jei.jasm.workshop.advanced", percent(odds.advanced())));
        builder.addText(lines, getWidth() - TEXT_X, getHeight()).setPosition(TEXT_X, 1);
    }

    private static String percent(double chance) {
        double value = chance * 100;
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
