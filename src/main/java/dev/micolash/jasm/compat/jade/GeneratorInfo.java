package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
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

/** Whether a Combustion Generator is burning, how long the current fuel lasts, and how much power it is making. */
public class GeneratorInfo implements StreamServerDataProvider<BlockAccessor, GeneratorInfo.Data> {
    public static final GeneratorInfo INSTANCE = new GeneratorInfo();

    public record Data(int burnLeft, int fePerTick) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::burnLeft,
                ByteBufCodecs.VAR_INT, Data::fePerTick,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof CombustionGeneratorBlockEntity generator
                ? new Data(generator.burnLeft(), generator.tier().fePerTick())
                : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("combustion_generator");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends GeneratorInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                if (data.burnLeft() > 0) {
                    tooltip.add(Component.translatable("jade.jasm.generator.burning", (data.burnLeft() + 19) / 20,
                            String.format("%,d", data.fePerTick())));
                } else {
                    tooltip.add(Component.translatable("jade.jasm.generator.idle"));
                }
            });
        }
    }
}
