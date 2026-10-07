package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.WaferSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Jasm.MODID)
public final class BayNetwork {
    private BayNetwork() {}

    public record Filter(int menu, WaferSettings filter) implements CustomPacketPayload {
        public static final Type<Filter> TYPE = new Type<>(Jasm.id("bay_filter"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Filter> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Filter::menu, WaferSettings.STREAM_CODEC, Filter::filter, Filter::new);
        @Override
        public Type<Filter> type() { return TYPE; }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Filter.TYPE, Filter.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof BayMenu menu && menu.containerId == payload.menu()
                    && menu.stillValid(context.player()))
                menu.configureFilter(payload.filter());
        });
    }
}
