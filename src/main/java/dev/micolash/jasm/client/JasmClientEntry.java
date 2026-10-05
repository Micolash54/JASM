package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Runs on the client only, so a dedicated server never loads the guide's layout classes. */
@Mod(value = Jasm.MODID, dist = Dist.CLIENT)
public final class JasmClientEntry {
    public JasmClientEntry(IEventBus modBus) {
        JasmGuide.build();
    }
}
