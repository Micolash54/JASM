package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CardRecipes;
import dev.micolash.jasm.autocraft.RecipeCard;
import dev.micolash.jasm.registry.JasmComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * A crafting card whose recipe is gone, or no longer makes the card's result from the card's grid, says so in red under
 * its name. Such a card never crafts, and nothing else is crafted in its place.
 */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class CardTooltips {
    private CardTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        RecipeCard card = event.getItemStack().get(JasmComponents.RECIPE_CARD.get());
        var level = Minecraft.getInstance().level;
        RecipeMap recipes = ReceivedRecipes.all();
        // Before the server has sent its recipes there is nothing to check against.
        if (card == null || level == null || recipes.byType(RecipeType.CRAFTING).isEmpty()) {
            return;
        }
        if (!CardRecipes.works(recipes.byKey(card.recipe()), card, level)) {
            event.getToolTip().add(Math.min(1, event.getToolTip().size()),
                    Component.translatable("tooltip.jasm.card.recipe_missing").withStyle(ChatFormatting.RED));
        }
    }
}
