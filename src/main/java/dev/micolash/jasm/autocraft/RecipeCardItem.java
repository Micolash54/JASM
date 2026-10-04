package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * A Filled Recipe Card: its tooltip says what it makes and what goes in, and whether the recipe is shaped, shapeless,
 * or for machines (and which).
 */
public class RecipeCardItem extends Item {
    public RecipeCardItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        RecipeCard card = stack.get(JasmComponents.RECIPE_CARD.get());
        if (card != null) {
            crafting(card, builder);
            return;
        }
        ProcessingCard processing = stack.get(JasmComponents.PROCESSING_CARD.get());
        if (processing != null) {
            processing(processing, builder);
        }
    }

    private static void crafting(RecipeCard card, Consumer<Component> builder) {
        ItemStack result = card.result();
        builder.accept(Component.translatable("tooltip.jasm.card.makes", result.getCount(), result.getHoverName()).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable(card.shapeless() ? "tooltip.jasm.card.shapeless" : "tooltip.jasm.card.shaped")
                .withStyle(card.shapeless() ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, Component> names = new LinkedHashMap<>();
        for (ItemStack input : card.inputs()) {
            if (!input.isEmpty()) {
                counts.merge(input.getItem(), 1, Integer::sum);
                names.putIfAbsent(input.getItem(), input.getHoverName());
            }
        }
        counts.forEach((item, n) -> builder.accept(
                Component.translatable("tooltip.jasm.card.input", n, names.get(item)).withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static void processing(ProcessingCard card, Consumer<Component> builder) {
        ProcessingCard.Amount main = card.main();
        if (!main.isEmpty()) {
            builder.accept(Component.translatable(main.isFluid() ? "tooltip.jasm.card.makes_fluid" : "tooltip.jasm.card.makes", main.label(),
                    main.stack().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        for (ProcessingCard.Amount extra : card.extras()) {
            builder.accept(Component.translatable(extra.isFluid() ? "tooltip.jasm.card.also_fluid" : "tooltip.jasm.card.also", extra.label(),
                    extra.stack().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable("tooltip.jasm.card.processing").withStyle(ChatFormatting.LIGHT_PURPLE));
        Map<GridKey, Integer> counts = new LinkedHashMap<>();
        for (ProcessingCard.Amount input : card.usedInputs()) {
            counts.merge(input.key(), input.count(), Integer::sum);
        }
        counts.forEach((key, n) -> {
            boolean fluid = key instanceof GridKey.Fluid;
            builder.accept(Component.translatable(fluid ? "tooltip.jasm.card.input_fluid" : "tooltip.jasm.card.input",
                    fluid ? FluidAmounts.label(n) : String.valueOf(n), ProcessingCard.Amount.of(key, 1).stack().getHoverName())
                    .withStyle(ChatFormatting.DARK_GRAY));
        });
        for (ProcessingCard.Machine machine : card.machines()) {
            builder.accept(Component.translatable("tooltip.jasm.card.machine", machine.name(), machine.pos().getX(), machine.pos().getY(),
                    machine.pos().getZ()).withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
