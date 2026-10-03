package dev.micolash.jasm.core;

import dev.micolash.jasm.Jasm;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jspecify.annotations.Nullable;

/**
 * The temporary, unsaved bookkeeping of one running server: rate limits, retry timers and the like. It is made when
 * the server starts and dropped when it stops, so nothing here can leak into the next world a single-player game
 * opens. A player's entries go when they log out, a rule's when it is deleted.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class JasmServerData {
    private static @Nullable JasmServerData current;

    /** When a player was last answered for the Deck's network tab, and for which screen. */
    public record NetworkAnswer(long tick, int containerId) {}

    /** Crafting Deck rules: how far each timed rule has counted, when each may try again, and why one is stuck. */
    public final Map<UUID, Integer> ruleCounted = new HashMap<>();
    public final Map<UUID, Long> ruleRetryAt = new HashMap<>();
    public final Map<UUID, String> ruleStalled = new HashMap<>();
    /** When each player last asked for a craft plan, in server ticks. */
    public final Map<UUID, Long> planAsked = new HashMap<>();
    /** The last answer given to each player for the Deck's network tab. */
    public final Map<UUID, NetworkAnswer> networkAsked = new HashMap<>();
    /** Deck operations handled so far this tick, per player: {tick, count}. */
    public final Map<UUID, long[]> deckOps = new HashMap<>();
    /** Owners with a name lookup running at an Encoding Terminal; one at a time each. */
    public final Set<UUID> nameLookups = new HashSet<>();

    private final MinecraftServer server;

    private JasmServerData(MinecraftServer server) {
        this.server = server;
    }

    /** The data of this server. */
    public static JasmServerData of(MinecraftServer server) {
        JasmServerData data = current;
        if (data == null || data.server != server) {
            data = new JasmServerData(server);
            current = data;
        }
        return data;
    }

    /** A player left: their entries go. */
    public void forgetPlayer(UUID player) {
        planAsked.remove(player);
        networkAsked.remove(player);
        deckOps.remove(player);
    }

    /** A rule was deleted: its count, timer and last problem go with it. */
    public void forgetRule(UUID rule) {
        ruleCounted.remove(rule);
        ruleRetryAt.remove(rule);
        ruleStalled.remove(rule);
    }

    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent event) {
        current = new JasmServerData(event.getServer());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        current = null;
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        JasmServerData data = current;
        if (data != null) {
            data.forgetPlayer(event.getEntity().getUUID());
        }
    }
}
