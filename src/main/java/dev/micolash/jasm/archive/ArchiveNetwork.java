package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/** Registers the Archive messages. A request only counts if that exact Archive screen is open and still usable. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class ArchiveNetwork {
    private ArchiveNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ArchivePayloads.Request.TYPE, ArchivePayloads.Request.STREAM_CODEC,
                        (payload, context) -> request((ServerPlayer) context.player(), payload))
                .playToClient(ArchivePayloads.State.TYPE, ArchivePayloads.State.STREAM_CODEC, ArchiveNetwork::onState)
                .playToClient(ArchivePayloads.Feedback.TYPE, ArchivePayloads.Feedback.STREAM_CODEC, ArchiveNetwork::onFeedback);
    }

    /** Returns null when the request was ignored (wrong or stale screen, or no longer allowed to use it). */
    public static ArchiveService.@Nullable Result request(ServerPlayer player, ArchivePayloads.Request request) {
        if (player.containerMenu instanceof ArchiveMenu menu && menu.containerId == request.containerId() && menu.stillValid(player)) {
            return menu.handle(player, request);
        }
        return null;
    }

    private static void onFeedback(ArchivePayloads.Feedback feedback, IPayloadContext context) {
        if (context.player().containerMenu instanceof ArchiveMenu menu && menu.containerId == feedback.containerId()) {
            menu.setFeedback(feedback);
        }
    }

    private static void onState(ArchivePayloads.State state, IPayloadContext context) {
        if (context.player().containerMenu instanceof ArchiveMenu menu && menu.containerId == state.containerId()) {
            menu.setView(state);
        }
    }
}
