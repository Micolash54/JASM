package dev.micolash.jasm.transfer;

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
public final class TransferNetwork {
    private TransferNetwork() {}
    public record Configure(int menu, boolean output, WaferSettings settings) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(Jasm.id("transfer_filters"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Configure> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Configure::menu, ByteBufCodecs.BOOL, Configure::output, WaferSettings.STREAM_CODEC, Configure::settings, Configure::new);
        @Override public Type<Configure> type() { return TYPE; }
    }
    @SubscribeEvent static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Configure.TYPE, Configure.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof TransferPortMenu menu && menu.containerId == payload.menu() && menu.stillValid(context.player())) menu.configure(payload.output(), payload.settings());
        });
    }
}
