package dev.micolash.jasm;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/**
 * Short messages for the player, shown inside the JASM screen they have open: a red line when something was refused,
 * a green one when it worked, for a few seconds. With no JASM screen open they go above the hotbar instead.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Notices {
    /** How long a message stays on screen, in ticks. */
    public static final int TICKS = 80;

    /** A menu whose screen shows messages. */
    public interface Board {
        Shown notices();
    }

    /** The last message a screen was sent, and when it arrived. Client side. */
    public static final class Shown {
        private @Nullable Component message;
        private boolean ok;
        private long time;

        public void show(Component message, boolean ok, long time) {
            this.message = message;
            this.ok = ok;
            this.time = time;
        }

        /** The message while it is still fresh at {@code now} (game time), or null. */
        public @Nullable Component current(long now) {
            return message != null && now - time >= 0 && now - time < TICKS ? message : null;
        }

        public boolean ok() {
            return ok;
        }
    }

    public record Notice(int containerId, Component message, boolean ok) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(Jasm.id("notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Notice::containerId,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notice::message,
                ByteBufCodecs.BOOL, Notice::ok,
                Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }

    private Notices() {}

    /** Something was refused or went wrong. */
    public static void bad(@Nullable Player player, Component message) {
        tell(player, message, false);
    }

    /** Something worked. */
    public static void good(@Nullable Player player, Component message) {
        tell(player, message, true);
    }

    public static void tell(@Nullable Player player, Component message, boolean ok) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.containerMenu instanceof Board && serverPlayer.connection.hasChannel(Notice.TYPE)) {
            PacketDistributor.sendToPlayer(serverPlayer, new Notice(serverPlayer.containerMenu.containerId, message, ok));
        } else {
            serverPlayer.sendOverlayMessage(message);
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Notice.TYPE, Notice.STREAM_CODEC, Notices::onNotice);
    }

    private static void onNotice(Notice notice, IPayloadContext context) {
        Player player = context.player();
        if (player.containerMenu instanceof Board board && player.containerMenu.containerId == notice.containerId()) {
            board.notices().show(notice.message(), notice.ok(), player.level().getGameTime());
        }
    }
}
