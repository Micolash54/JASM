package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;

/** Client-only setup. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class JasmClient {
    private JasmClient() {}

    /** Lets item models pick a Deck's picture by how charged it is. */
    @SubscribeEvent
    static void registerItemProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Jasm.id("deck_charge"), DeckCharge.MAP_CODEC);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(JasmBlocks.RECIPE_RACK_ENTITY.get(), RecipeRackRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.CRAFTING_SERVER_ENTITY.get(), CraftingServerRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.ENCODING_TERMINAL_ENTITY.get(), EncodingTerminalRenderer::new);
    }

    /** The cards, parts, fans and screens the crafting blocks draw on top of themselves. */
    @SubscribeEvent
    static void registerModels(ModelEvent.RegisterStandalone event) {
        event.register(RecipeRackRenderer.CARD_MODEL, SimpleUnbakedStandaloneModel.simpleModelWrapper(RecipeRackRenderer.CARD_MODEL_ID));
        CraftingServerRenderer.registerModels(event);
        EncodingTerminalRenderer.registerModels(event);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(JasmMenus.DECK.get(), DeckScreen::new);
        event.register(JasmMenus.ARCHIVE.get(), ArchiveScreen::new);
        event.register(JasmMenus.CREATIVE_BATTERY.get(), CreativeBatteryScreen::new);
        event.register(JasmMenus.COMBUSTION_GENERATOR.get(), CombustionGeneratorScreen::new);
        event.register(JasmMenus.ENCODING_TERMINAL.get(), EncodingTerminalScreen::new);
        event.register(JasmMenus.RECIPE_RACK.get(), RecipeRackScreen::new);
        event.register(JasmMenus.CRAFTING_SERVER.get(), CraftingServerScreen::new);
        event.register(JasmMenus.ACCESS_PORT.get(), AccessPortScreen::new);
    }
}
