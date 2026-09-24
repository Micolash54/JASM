package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler;

/** The Deck battery: anything that charges items can fill it; nothing can drain it from outside. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DeckCapabilities {
    private DeckCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        for (DeckTier tier : DeckTier.values()) {
            event.registerItem(Capabilities.Energy.ITEM,
                    (stack, access) -> new ItemAccessEnergyHandler(access, JasmComponents.ENERGY.get(), tier.battery(), tier.battery(), 0),
                    JasmItems.deck(tier));
        }
    }
}
