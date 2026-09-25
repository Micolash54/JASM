package dev.micolash.jasm.generator;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Other blocks may pull power from any side, but not push it in. Hoppers and pipes reach both slots from any side. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class CombustionGeneratorCapabilities {
    private CombustionGeneratorCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.COMBUSTION_GENERATOR_ENTITY.get(), (generator, side) -> generator.output());
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.COMBUSTION_GENERATOR_ENTITY.get(), (generator, side) -> generator.automation());
    }
}
