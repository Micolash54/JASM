package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The Power Acceptor takes FE on every side. It is the only JASM block that does. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class PowerAcceptorCapabilities {
    private PowerAcceptorCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), (acceptor, side) -> acceptor.input());
    }
}
