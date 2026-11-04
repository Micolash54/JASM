package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.tether.NetworkTetherBlockEntity;
import dev.micolash.jasm.tether.TetherStatus;
import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/** A Network Tether: what it is doing, and how many chunks it holds. */
public class TetherInfo implements StreamServerDataProvider<BlockAccessor, TetherInfo.Data> {
    public static final TetherInfo INSTANCE = new TetherInfo();

    public record Data(int status, int chunks) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::status,
                ByteBufCodecs.VAR_INT, Data::chunks,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof NetworkTetherBlockEntity tether)) return null;
        return new Data(tether.status().ordinal(), tether.chunksHeld());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("network_tether");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends TetherInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                TetherStatus status = TetherStatus.values()[Math.clamp(data.status(), 0, TetherStatus.values().length - 1)];
                tooltip.add(Component.translatable("jade.jasm.network_tether." + status.name().toLowerCase(Locale.ROOT)));
                tooltip.add(Component.translatable("screen.jasm.network_tether.chunks", data.chunks()));
            });
        }
    }
}
