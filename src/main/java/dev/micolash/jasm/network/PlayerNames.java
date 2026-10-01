package dev.micolash.jasm.network;

import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/** Finds a player's name from their id, for players who may be offline. */
public final class PlayerNames {
    private PlayerNames() {}

    /** Online players first, then the server's name cache, then the trust lists on {@code network}; empty if unknown. */
    public static String of(MinecraftServer server, UUID id, @Nullable CableNetwork network) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getName().getString();
        var cached = server.services().nameToIdCache().get(id);
        if (cached.isPresent()) return cached.get().name();
        if (network != null) {
            for (var terminal : network.machines(EncodingTerminalBlockEntity.class)) {
                for (var entry : terminal.trust().entries()) if (entry.id().equals(id)) return entry.name();
            }
        }
        return "";
    }
}
