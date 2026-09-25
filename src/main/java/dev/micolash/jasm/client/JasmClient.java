package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-only setup. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class JasmClient {
    private JasmClient() {}

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(JasmMenus.DECK.get(), DeckScreen::new);
        event.register(JasmMenus.CREATIVE_BATTERY.get(), CreativeBatteryScreen::new);
    }
}
