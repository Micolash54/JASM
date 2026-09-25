package dev.micolash.jasm;

import com.mojang.logging.LogUtils;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.registry.JasmTabs;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(Jasm.MODID)
public final class Jasm {
    public static final String MODID = "jasm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jasm(IEventBus modBus, ModContainer container) {
        JasmComponents.COMPONENTS.register(modBus);
        JasmBlocks.BLOCKS.register(modBus);
        JasmBlocks.BLOCK_ENTITIES.register(modBus);
        JasmItems.ITEMS.register(modBus);
        JasmTabs.TABS.register(modBus);
        JasmMenus.MENUS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, JasmConfig.SPEC);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
