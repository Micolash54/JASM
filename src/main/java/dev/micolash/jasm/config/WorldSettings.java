package dev.micolash.jasm.config;

import dev.micolash.jasm.Jasm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * A singleplayer world keeps its own copy of the world rules. The first time it opens, it gets a copy of the shared file in
 * the config folder, and NeoForge reads and saves that copy instead. Dedicated servers keep using the shared file.
 */
public final class WorldSettings {
    private static final String FILE = "jasm-server.toml";
    private static final String PRESET_NOTE = "jasm-preset.txt";

    private WorldSettings() {}

    private static Path folder(MinecraftServer server) {
        return server.getWorldPath(new LevelResource("serverconfig"));
    }

    /** True while a singleplayer world has no copy yet, so the shared file is about to be copied for it. */
    public static boolean waitingForCopy(MinecraftServer server) {
        return !server.isDedicatedServer() && !Files.exists(folder(server).resolve(FILE));
    }

    /**
     * Copies the shared file into the world's folder, along with a note that the copy already has its preset's numbers.
     * Does nothing when the world already has a copy or there is no shared file. Returns whether a copy was made.
     */
    public static boolean copyInto(Path shared, Path folder, Preset preset) {
        Path copy = folder.resolve(FILE);
        if (Files.exists(copy) || !Files.exists(shared)) {
            return false;
        }
        try {
            Files.createDirectories(folder);
            Files.copy(shared, copy);
            Files.writeString(folder.resolve(PRESET_NOTE), preset.name());
            return true;
        } catch (IOException e) {
            Jasm.LOGGER.warn("Could not give this world its own copy of the JASM settings", e);
            return false;
        }
    }

    /** Runs once the server has read the shared file: copies it for a new world, then reads the world's copy instead. */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        if (!waitingForCopy(server)) {
            return;
        }
        Path shared = FMLPaths.CONFIGDIR.get().resolve(FILE);
        if (copyInto(shared, folder(server), JasmConfig.PRESET.get())) {
            ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, FMLPaths.CONFIGDIR.get(), folder(server));
        }
    }
}
