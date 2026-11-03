package dev.micolash.jasm.config;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.client.JasmSettingsScreen;
import io.netty.buffer.ByteBuf;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The two messages of the settings screen: the changed world rules going up, and what became of them coming back. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class SettingsPayloads {
    private SettingsPayloads() {}

    private static final int MOST_CHANGES = 256;

    /** The world rules a player changed, setting id to value text. */
    public record Save(Map<String, String> changes) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(Jasm.id("settings_save"));
        public static final StreamCodec<ByteBuf, Save> STREAM_CODEC = ByteBufCodecs
                .map(LinkedHashMap::new, ByteBufCodecs.stringUtf8(64), ByteBufCodecs.stringUtf8(2048), MOST_CHANGES)
                .map(Save::new, save -> new LinkedHashMap<>(save.changes()));

        @Override
        public Type<Save> type() {
            return TYPE;
        }
    }

    /** What the server made of a {@link Save}. */
    public record Saved(SettingsSave.Status status) implements CustomPacketPayload {
        public static final Type<Saved> TYPE = new Type<>(Jasm.id("settings_saved"));
        public static final StreamCodec<ByteBuf, Saved> STREAM_CODEC = ByteBufCodecs
                .idMapper(i -> SettingsSave.Status.values()[i], SettingsSave.Status::ordinal)
                .map(Saved::new, Saved::status);

        @Override
        public Type<Saved> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Save.TYPE, Save.STREAM_CODEC, SettingsPayloads::onSave)
                .playToClient(Saved.TYPE, Saved.STREAM_CODEC, SettingsPayloads::onSaved);
    }

    private static void onSave(Save payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        SettingsSave.Status status = SettingsSave.handle(player, payload.changes());
        if (status == SettingsSave.Status.SAVED && !payload.changes().isEmpty()) {
            Jasm.LOGGER.info("{} changed the JASM world rules: {}", player.getName().getString(), payload.changes().keySet());
        }
        context.reply(new Saved(status));
    }

    private static void onSaved(Saved payload, IPayloadContext context) {
        JasmSettingsScreen.saved(payload.status());
    }
}
