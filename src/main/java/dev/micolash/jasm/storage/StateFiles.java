package dev.micolash.jasm.storage;

import dev.micolash.jasm.Jasm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Protects the JASM state file. Vanilla replaces saved data that fails to load with a fresh empty instance and
 * overwrites the file on the next save; for JASM that would lose the Archive records and the stamp history.
 */
final class StateFiles {
    private StateFiles() {}

    static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATA).resolve(Jasm.MODID);
    }

    static Path stateFile(MinecraftServer server) {
        return folder(server).resolve("state.dat");
    }

    static Path backupFile(MinecraftServer server) {
        return folder(server).resolve("state.dat.startup-bak");
    }

    static Path waferFolder(MinecraftServer server) {
        return folder(server).resolve("wafers");
    }

    /**
     * Runs before the state is first used. Refuses to continue if the file cannot be read, or if it is missing
     * while a backup or wafer records exist. Only a file that loaded correctly replaces the previous backup.
     */
    static void guardLoad(MinecraftServer server) {
        Path file = stateFile(server);
        Path backup = backupFile(server);
        try {
            if (!Files.exists(file)) {
                if (Files.exists(backup)) {
                    throw new IllegalStateException("JASM state " + file + " is missing, but a backup exists at " + backup
                            + ". Copy the backup to state.dat to restore it, or delete the backup to start fresh"
                            + " (Archive records would be lost).");
                }
                if (hasWaferRecords(server)) {
                    throw new IllegalStateException("JASM state " + file + " is missing, but wafer records exist in "
                            + waferFolder(server) + ". Restore state.dat from a world backup.");
                }
                return;
            }
            if (server.getDataStorage().get(JasmState.TYPE) == null) {
                Path corrupt = file.resolveSibling("state.corrupt-" + System.currentTimeMillis() + ".dat");
                Files.copy(file, corrupt);
                throw new IllegalStateException("JASM state " + file + " failed to load. A copy was saved to " + corrupt
                        + ". Refusing to continue with an empty state. The last good copy is " + backup + ".");
            }
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not back up JASM state " + file, e);
        }
    }

    /** A new world has no backup until its first state save; make one then. */
    static void backUpIfMissing(MinecraftServer server) {
        Path file = stateFile(server);
        Path backup = backupFile(server);
        if (Files.exists(backup) || !Files.exists(file)) {
            return;
        }
        try {
            Files.copy(file, backup);
        } catch (IOException e) {
            Jasm.LOGGER.error("Could not back up JASM state {}", file, e);
        }
    }

    private static boolean hasWaferRecords(MinecraftServer server) throws IOException {
        Path wafers = waferFolder(server);
        if (!Files.isDirectory(wafers)) {
            return false;
        }
        try (Stream<Path> files = Files.list(wafers)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".mca"));
        }
    }
}
