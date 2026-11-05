package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Ports, hoppers and pipes reach a bay's grid, tank and power through the faces its I/O grid opens. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BayCapabilities {
    private BayCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), (bay, side) -> bay.itemsThrough(side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DEMOLITION_BAY_ENTITY.get(), (bay, side) -> bay.itemsThrough(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), (bay, side) -> bay.fluidsThrough(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.DEMOLITION_BAY_ENTITY.get(), (bay, side) -> bay.fluidsThrough(side));
        // The only JASM blocks other mods' cables, pipes and generators power straight, through their Input energy faces.
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), (bay, side) -> bay.energyThrough(side));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.DEMOLITION_BAY_ENTITY.get(), (bay, side) -> bay.energyThrough(side));
    }
}
