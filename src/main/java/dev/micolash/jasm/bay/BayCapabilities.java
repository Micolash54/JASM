package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Ports, hoppers and pipes reach a bay's grid and tank from every side. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BayCapabilities {
    private BayCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), (bay, side) -> bay.itemAutomation());
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DEMOLITION_BAY_ENTITY.get(), (bay, side) -> bay.itemAutomation());
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), (bay, side) -> bay.fluidAutomation());
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.DEMOLITION_BAY_ENTITY.get(), (bay, side) -> bay.fluidAutomation());
    }
}
