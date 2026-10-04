package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.bay.BayBlockEntity;
import dev.micolash.jasm.bay.BayStatus;
import dev.micolash.jasm.bay.DemolitionBayBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/** A bay: what it is doing, and a Demolition Bay's enchantments. */
public class BayInfo implements StreamServerDataProvider<BlockAccessor, BayInfo.Data> {
    public static final BayInfo INSTANCE = new BayInfo();

    public record Data(int status, List<Component> enchantments) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::status,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Data::enchantments,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof BayBlockEntity bay)) return null;
        List<Component> enchantments = new ArrayList<>();
        if (bay instanceof DemolitionBayBlockEntity demolition) {
            for (var entry : demolition.enchantments().entrySet()) {
                enchantments.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()));
            }
        }
        return new Data(bay.status().ordinal(), enchantments);
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("bay");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends BayInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                tooltip.add(Component.translatable(BayStatus.byId(data.status()).key()));
                data.enchantments().forEach(tooltip::add);
            });
        }
    }
}
