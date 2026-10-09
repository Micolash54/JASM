package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The Power Acceptor takes FE on every side, and a thin one on a cable takes it on its own face. Nothing else of JASM's does. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class PowerAcceptorCapabilities {
    private PowerAcceptorCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), (acceptor, side) -> acceptor.input(side));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(),
                (cable, side) -> side == null ? null : cable.acceptorInput(side));
    }
}
