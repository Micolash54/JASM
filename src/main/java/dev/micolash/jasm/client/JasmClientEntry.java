package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Runs on the client only, so a dedicated server never loads the guide's layout classes or the settings screen. */
@Mod(value = Jasm.MODID, dist = Dist.CLIENT)
public final class JasmClientEntry {
    public JasmClientEntry(IEventBus modBus, ModContainer container) {
        JasmGuide.build();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigurationScreen(mod, parent, ChanceSliders::filter));
    }
}
