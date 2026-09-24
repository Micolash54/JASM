package dev.micolash.jasm.storage;

import dev.micolash.jasm.Jasm;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Keeps wafer records in step with player files. After a crash each file goes back to its own last save, so a
 * record is only written once the player files it has to agree with are on disk.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class StorageEvents {
    private StorageEvents() {}

    /** New epoch before any player can join, saved immediately so it can never be reused. */
    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        WaferStore store;
        try {
            store = WaferStore.get(server);
        } catch (RuntimeException unsafe) {
            // Running on would leave every wafer unusable; stopping keeps the files exactly as they are for the admin.
            Jasm.LOGGER.error("JASM is stopping the server: {}", unsafe.getMessage());
            server.halt(false);
            return;
        }
        store.state().beginEpoch();
        server.getDataStorage().saveAndJoin();
        StateFiles.backUpIfMissing(server);
        Jasm.LOGGER.info("JASM storage ready: epoch {}, next wafer #{}", store.state().epoch(), store.state().nextSerial());
    }

    /**
     * Fires after a player file has been written (logout, autosave, /save-all, shutdown). During a full save every
     * player is written first and the records follow in {@link #onLevelSaved}; on its own (logging out) the
     * player's records are written straight away.
     */
    @SubscribeEvent
    static void onPlayerSaved(PlayerEvent.SaveToFile event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        afterPlayerSaved(server, player, server.getPlayerList().getPlayers());
    }

    /** Also used by tests, which pass their own list of online players. */
    public static void afterPlayerSaved(MinecraftServer server, ServerPlayer player, List<ServerPlayer> online) {
        WaferStore store = WaferStore.ifOpen(server);
        if (store != null) {
            store.afterPlayerSaved(player, online, server.isCurrentlySaving());
        }
    }

    /** Full saves write every player first, then every level; the overworld's save is our turn. */
    @SubscribeEvent
    static void onLevelSaved(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            WaferStore store = WaferStore.ifOpen(level.getServer());
            if (store != null) {
                store.writeAllDirty();
            }
        }
    }

    /**
     * The overworld unloads during shutdown right after the final save, while the game's disk threads still run
     * (they are gone by the time the server reports it has stopped). Vanilla closes its own region files here too.
     */
    @SubscribeEvent
    static void onLevelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            WaferStore.close(level.getServer());
        }
    }
}
