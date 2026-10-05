package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crystal.QuenchingRecipe;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.workshop.WorkshopRecipe;
import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/** JASM's own recipe types as the server last sent them, for recipe viewers. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class ReceivedRecipes {
    private static List<QuenchingRecipe> quenching = List.of();
    private static List<WorkshopRecipe> workshop = List.of();

    private ReceivedRecipes() {}

    @SubscribeEvent
    static void onRecipes(RecipesReceivedEvent event) {
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
}
