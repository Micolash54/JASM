package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.LimitingEnergyHandler;

/** Any side of an Archive takes FE; nothing can pull it back out. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class ArchiveCapabilities {
    private ArchiveCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.ARCHIVE_ENTITY.get(),
                (archive, side) -> new LimitingEnergyHandler(archive.energy(), Integer.MAX_VALUE, 0));
    }
}
