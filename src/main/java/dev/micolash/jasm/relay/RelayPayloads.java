package dev.micolash.jasm.relay;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Messages from the Relay Chatterbox's panel to the server. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class RelayPayloads {
    private RelayPayloads() {}

    /**
     * A new frequency, 0 to 999 or {@link RelayEnd#NO_FREQUENCY} to clear it. {@code shown} is the frequency the panel
     * showed, so a request made about one that has changed since is dropped.
     */
    public record SetFrequency(int containerId, int shown, int frequency) implements CustomPacketPayload {
        public static final Type<SetFrequency> TYPE = new Type<>(Jasm.id("relay_frequency"));
        public static final StreamCodec<ByteBuf, SetFrequency> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetFrequency::containerId,
                ByteBufCodecs.VAR_INT, SetFrequency::shown,
                ByteBufCodecs.VAR_INT, SetFrequency::frequency,
                SetFrequency::new);

        @Override
        public Type<SetFrequency> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SetFrequency.TYPE, SetFrequency.STREAM_CODEC,
                (payload, context) -> setFrequency((ServerPlayer) context.player(), payload));
    }

    /** Only counts if that exact panel is open and the player may still use it. */
    public static void setFrequency(ServerPlayer player, SetFrequency payload) {
        if (player.containerMenu instanceof RelayChatterboxMenu menu && menu.containerId == payload.containerId()) {
            menu.setFrequency(player, payload.shown(), payload.frequency());
        }
    }
}
