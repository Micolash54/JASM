package dev.micolash.jasm.workshop;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler;

/** A critter's battery charges and drains like any powered item. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BitlingCapabilities {
    private BitlingCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        for (BitlingItem bitling : JasmItems.bitlings()) {
            event.registerItem(Capabilities.Energy.ITEM, (stack, access) -> {
                int battery = bitling.battery();
                return new ItemAccessEnergyHandler(access, JasmComponents.ENERGY.get(), battery, battery, battery);
            }, bitling);
        }
    }
}
