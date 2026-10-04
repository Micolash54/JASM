package dev.micolash.jasm.pool;

import dev.micolash.jasm.Jasm;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Jasm.MODID)
public final class StorageNetwork {
    private StorageNetwork() {}

    public record Configure(int menu, StorageSettings settings) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(Jasm.id("storage_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Configure> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Configure::menu, StorageSettings.STREAM_CODEC, Configure::settings, Configure::new);
        @Override
        public Type<Configure> type() { return TYPE; }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Configure.TYPE, Configure.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof StoragePortMenu menu && menu.containerId == payload.menu()
                    && menu.stillValid(context.player()))
                menu.configure(payload.settings());
        });
    }
}
