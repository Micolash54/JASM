package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

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
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(JasmMenus.DECK.get(), DeckScreen::new);
        event.register(JasmMenus.ARCHIVE.get(), ArchiveScreen::new);
        event.register(JasmMenus.CREATIVE_BATTERY.get(), CreativeBatteryScreen::new);
        event.register(JasmMenus.COMBUSTION_GENERATOR.get(), CombustionGeneratorScreen::new);
    }
}
