package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmEntities;
import dev.micolash.jasm.registry.JasmMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;

/** Client-only setup. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class JasmClient {
    private JasmClient() {}

    @SubscribeEvent
    static void registerBlockExtensions(RegisterClientExtensionsEvent event) {
        event.registerBlock(new DataCableClientExtensions(), JasmBlocks.cables().stream().map(b -> (net.minecraft.world.level.block.Block) b.get()).toArray(net.minecraft.world.level.block.Block[]::new));
    }

    /** Lets item models pick a Deck's picture by how charged it is. */
    @SubscribeEvent
    static void registerItemProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Jasm.id("deck_charge"), DeckCharge.MAP_CODEC);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.RECIPE_RACK_ENTITY.get(), RecipeRackRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.CRAFTING_SERVER_ENTITY.get(), CraftingServerRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.ENCODING_TERMINAL_ENTITY.get(), EncodingTerminalRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), ChipWorkshopRenderer::new);
        event.registerBlockEntityRenderer(JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), CrystalFoundryRenderer::new);
        event.registerEntityRenderer(JasmEntities.STATION_BITLING.get(), StationBitlingRenderer::new);
    }

    /** The cards, parts, fans and screens the crafting blocks draw on top of themselves. */
    @SubscribeEvent
    static void registerModels(ModelEvent.RegisterStandalone event) {
        event.register(RecipeRackRenderer.CARD_MODEL, SimpleUnbakedStandaloneModel.simpleModelWrapper(RecipeRackRenderer.CARD_MODEL_ID));
        event.register(DataCableRenderer.PORT, SimpleUnbakedStandaloneModel.simpleModelWrapper(DataCableRenderer.PORT_ID));
        CraftingServerRenderer.registerModels(event);
        EncodingTerminalRenderer.registerModels(event);
        ChipWorkshopRenderer.registerModels(event);
        CrystalFoundryRenderer.registerModels(event);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(JasmMenus.DECK.get(), DeckScreen::new);
        event.register(JasmMenus.ARCHIVE.get(), ArchiveScreen::new);
        event.register(JasmMenus.CREATIVE_BATTERY.get(), CreativeBatteryScreen::new);
        event.register(JasmMenus.COMBUSTION_GENERATOR.get(), CombustionGeneratorScreen::new);
        event.register(JasmMenus.CHIP_WORKSHOP.get(), ChipWorkshopScreen::new);
        event.register(JasmMenus.CRYSTAL_FOUNDRY.get(), CrystalFoundryScreen::new);
        event.register(JasmMenus.BITLING_STATION.get(), BitlingStationScreen::new);
        event.register(JasmMenus.ENCODING_TERMINAL.get(), EncodingTerminalScreen::new);
        event.register(JasmMenus.RECIPE_RACK.get(), RecipeRackScreen::new);
        event.register(JasmMenus.CRAFTING_SERVER.get(), CraftingServerScreen::new);
        event.register(JasmMenus.ACCESS_PORT.get(), AccessPortScreen::new);
    }
}
