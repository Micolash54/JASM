package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.workshop.WorkshopRecipe;
import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/** The recipes as the server last sent them: JASM's own types for recipe viewers, crafting for the card tooltips. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class ReceivedRecipes {
    private static List<QuenchingRecipe> quenching = List.of();
    private static List<WorkshopRecipe> workshop = List.of();
    /** Every recipe the server sent (crafting among them), replaced on each sync. */
    private static RecipeMap all = RecipeMap.EMPTY;

    private ReceivedRecipes() {}

    @SubscribeEvent
    static void onRecipes(RecipesReceivedEvent event) {
        all = event.getRecipeMap();
        if (event.getRecipeTypes().contains(JasmRecipes.QUENCHING_TYPE.get())) {
            quenching = event.getRecipeMap().byType(JasmRecipes.QUENCHING_TYPE.get()).stream().map(RecipeHolder::value).toList();
        }
        if (event.getRecipeTypes().contains(JasmRecipes.WORKSHOP_TYPE.get())) {
            workshop = event.getRecipeMap().byType(JasmRecipes.WORKSHOP_TYPE.get()).stream().map(RecipeHolder::value).toList();
        }
    }

    public static List<QuenchingRecipe> quenching() {
        return quenching;
    }

    public static List<WorkshopRecipe> workshop() {
        return workshop;
    }

    /** The recipes as the server last sent them; empty before the first sync. */
    public static RecipeMap all() {
        return all;
    }
}
