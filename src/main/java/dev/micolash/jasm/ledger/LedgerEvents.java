package dev.micolash.jasm.ledger;

import dev.micolash.jasm.Jasm;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Save-consistency hooks. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class LedgerEvents {
    private LedgerEvents() {}

    /** New epoch before any player can join, persisted immediately so it can never be reused. */
    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        JasmLedger ledger = JasmLedger.get(server);
        ledger.beginEpoch();
        server.getDataStorage().saveAndJoin();
        Jasm.LOGGER.info("JASM ledger epoch {} started", ledger.epoch());
    }

    /**
     * Fires after a player file has been written (logout, autosave, stop). Writing the ledger right behind it keeps
     * player items and wafer contents from diverging if the server dies before the next full save.
     */
    @SubscribeEvent
    static void onPlayerSaved(PlayerEvent.SaveToFile event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        JasmLedger ledger = JasmLedger.get(server);
        if (!ledger.isDirty()) {
            return;
        }
        try {
            server.getDataStorage().saveAndJoin();
        } catch (IllegalStateException closed) {
            Jasm.LOGGER.debug("Ledger storage already closed during player save", closed);
        }
    }
}
