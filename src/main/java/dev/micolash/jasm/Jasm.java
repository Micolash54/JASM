package dev.micolash.jasm;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Jasm.MODID)
public final class Jasm {
    public static final String MODID = "jasm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jasm(IEventBus modBus, ModContainer container) {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
