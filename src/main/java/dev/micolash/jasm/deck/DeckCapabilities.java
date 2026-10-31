package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The Deck battery: anything that charges items can fill it; nothing can drain it from outside. The size is read each time
 * something asks for the battery, since the world's rules load after items register.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DeckCapabilities {
    private DeckCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        for (DeckItem deck : JasmItems.allDecks()) {
            event.registerItem(Capabilities.Energy.ITEM, (stack, access) -> {
                int battery = deck.tier().battery();
                return new ItemAccessEnergyHandler(access, JasmComponents.ENERGY.get(), battery, battery, 0) {
                    /** A Deck holding more than a lowered battery counts as full. */
                    @Override
                    protected int getAmountFrom(ItemResource accessResource) {
                        return Math.min(super.getAmountFrom(accessResource), capacity);
                    }
                };
            }, deck);
        }
    }
}
