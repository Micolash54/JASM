package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
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

/** How far a Crystal Foundry's current seed has got. */
public class FoundryInfo implements StreamServerDataProvider<BlockAccessor, FoundryInfo.Data> {
    public static final FoundryInfo INSTANCE = new FoundryInfo();

    /** {@code made} is -1 when no seed is growing. */
    public record Data(int made, int perSeed) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::made,
                ByteBufCodecs.VAR_INT, Data::perSeed,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof CrystalFoundryBlockEntity foundry
                ? new Data(foundry.growing() ? foundry.made() : -1, JasmConfig.FOUNDRY_CRYSTALS_PER_SEED.getAsInt())
                : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("crystal_foundry");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends FoundryInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> tooltip.add(data.made() < 0
                    ? Component.translatable("screen.jasm.foundry.no_seed")
                    : Component.translatable("screen.jasm.foundry.seed", Math.max(0, data.perSeed() - data.made()), data.perSeed())));
        }
    }
}
