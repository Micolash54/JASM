package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** A Filled Recipe Card: its tooltip says what it makes, whether the recipe is shaped, and what goes in. */
public class RecipeCardItem extends Item {
    public RecipeCardItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        RecipeCard card = stack.get(JasmComponents.RECIPE_CARD.get());
        if (card == null) {
            return;
        }
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
}
