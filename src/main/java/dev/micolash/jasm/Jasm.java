package dev.micolash.jasm;

import com.mojang.logging.LogUtils;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.crystal.Quenching;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmEntities;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.registry.JasmTabs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import org.slf4j.Logger;

@Mod(Jasm.MODID)
public final class Jasm {
    public static final String MODID = "jasm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jasm(IEventBus modBus, ModContainer container) {
        JasmComponents.COMPONENTS.register(modBus);
        JasmBlocks.BLOCKS.register(modBus);
        JasmBlocks.BLOCK_ENTITIES.register(modBus);
        JasmEntities.ENTITIES.register(modBus);
        JasmItems.ITEMS.register(modBus);
        JasmTabs.TABS.register(modBus);
        JasmMenus.MENUS.register(modBus);
        JasmRecipes.SERIALIZERS.register(modBus);
        JasmRecipes.TYPES.register(modBus);
        Quenching.ATTACHMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, JasmConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, JasmClientConfig.SPEC);
        // Players don't get crafting recipes from the server by default. Sending them lets recipe viewers like JEI
        // show JASM's recipes even when the server doesn't run the viewer itself.
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> event.sendRecipes(RecipeType.CRAFTING,
                JasmRecipes.QUENCHING_TYPE.get()));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
