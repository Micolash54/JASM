package dev.micolash.jasm.ledger;

import dev.micolash.jasm.Jasm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Protects the ledger file. Vanilla replaces saved data that fails to load with a fresh empty instance and
 * overwrites the file on the next save; for JASM that would silently delete every wafer's contents.
 */
final class LedgerFiles {
    private LedgerFiles() {}

    static Path ledgerFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATA).resolve(Jasm.MODID).resolve("ledger.dat");
    }

    static void guardLoad(MinecraftServer server) {
        Path file = ledgerFile(server);
        if (!Files.exists(file)) {
            return;
        }
        try {
            Files.copy(file, file.resolveSibling("ledger.dat.startup-bak"), StandardCopyOption.REPLACE_EXISTING);
            if (server.getDataStorage().get(JasmLedger.TYPE) == null) {
                Path corrupt = file.resolveSibling("ledger.corrupt-" + System.currentTimeMillis() + ".dat");
                Files.copy(file, corrupt);
                throw new IllegalStateException("JASM ledger " + file + " failed to load. A copy was saved to " + corrupt
                        + ". Refusing to continue with an empty ledger.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not back up JASM ledger " + file, e);
        }
    }
}
