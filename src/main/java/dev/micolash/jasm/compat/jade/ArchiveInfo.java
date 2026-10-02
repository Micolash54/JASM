package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.NetworkLimit;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.storage.ArchiveRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/**
 * An Archive's owner, how many wafers it has linked out of how many it can, and a warning when it has no power or its
 * network is full.
 */
public class ArchiveInfo implements StreamServerDataProvider<BlockAccessor, ArchiveInfo.Data> {
    public static final ArchiveInfo INSTANCE = new ArchiveInfo();

    /** {@code fullCount} and {@code fullLimit}: the network's machines and limit while it is full, both 0 otherwise. */
    public record Data(String owner, int linked, int slots, boolean powered, int fullCount, int fullLimit) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::owner,
                ByteBufCodecs.VAR_INT, Data::linked,
                ByteBufCodecs.VAR_INT, Data::slots,
                ByteBufCodecs.BOOL, Data::powered,
                ByteBufCodecs.VAR_INT, Data::fullCount,
                ByteBufCodecs.VAR_INT, Data::fullLimit,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof ArchiveBlockEntity archive)) {
            return null;
        }
        ArchiveRecord record = archive.record();
        NetworkLimit.State full = null;
        if (archive.networkStopped() && archive.getLevel() instanceof ServerLevel level
                && Networks.at(level, archive.getBlockPos()) instanceof CableNetwork network) {
            full = network.limitState();
        }
        return new Data(archive.ownerName(), record == null ? 0 : record.linked().size(), archive.tier().registrations(),
                archive.energy().getAmountAsInt() > 0, full == null ? 0 : full.count(), full == null ? 0 : full.limit());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("archive");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends ArchiveInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                if (!data.owner().isEmpty()) {
                    tooltip.add(Component.translatable("jade.jasm.archive.owner", data.owner()));
                }
                tooltip.add(Component.translatable("jade.jasm.archive.linked", data.linked(), data.slots()));
                if (data.fullLimit() > 0) {
                    tooltip.add(Component.translatable("screen.jasm.machine.network_full", data.fullCount(), data.fullLimit())
                            .withStyle(ChatFormatting.RED));
                } else if (!data.powered()) {
                    tooltip.add(Component.translatable("jade.jasm.archive.no_power").withStyle(ChatFormatting.RED));
                }
            });
        }
    }
}
