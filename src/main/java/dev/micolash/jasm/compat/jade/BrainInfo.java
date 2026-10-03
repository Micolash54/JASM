package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.brain.BrainStatus;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainMenu;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.NetworkLimit;
import dev.micolash.jasm.network.Networks;
import java.util.Locale;
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

/** A Network Brain, or a chamber of its floor: the network's machines, its tower's floors and what it is doing. */
public class BrainInfo implements StreamServerDataProvider<BlockAccessor, BrainInfo.Data> {
    public static final BrainInfo INSTANCE = new BrainInfo();

    /** {@code alone}: a chamber that isn't part of a floor; the other fields are then unused. */
    public record Data(boolean alone, int floors, int count, int limit, int status) {
        static final Data ALONE = new Data(true, 0, 0, 0, 0);

        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Data::alone,
                ByteBufCodecs.VAR_INT, Data::floors,
                ByteBufCodecs.VAR_INT, Data::count,
                ByteBufCodecs.VAR_INT, Data::limit,
                ByteBufCodecs.VAR_INT, Data::status,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        NetworkBrainBlockEntity brain;
        if (accessor.getBlockEntity() instanceof NetworkBrainBlockEntity found) {
            brain = found;
        } else if (accessor.getBlockEntity() instanceof NetworkChamberBlockEntity chamber) {
            brain = chamber.brain();
            if (brain == null) {
                return Data.ALONE;
            }
        } else {
            return null;
        }
        int count = 0;
        int limit = BrainBalance.fromConfig().limit(true, brain.floors());
        if (brain.getLevel() instanceof ServerLevel serverLevel && Networks.at(serverLevel, brain.getBlockPos()) instanceof CableNetwork network) {
            NetworkLimit.State state = network.limitState();
            count = state.count();
            limit = state.limit();
        }
        return new Data(false, brain.floors(), count, limit, brain.status().ordinal());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("network_brain");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends BrainInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                if (data.alone()) {
                    tooltip.add(Component.translatable("jade.jasm.chamber.alone"));
                    return;
                }
                BrainStatus status = BrainStatus.values()[Math.clamp(data.status(), 0, BrainStatus.values().length - 1)];
                tooltip.add(Component.translatable("screen.jasm.brain.machines", data.count(), data.limit()));
                tooltip.add(NetworkBrainMenu.floorsText(data.floors()));
                tooltip.add(Component.translatable("screen.jasm.brain.status." + status.name().toLowerCase(Locale.ROOT)));
            });
        }
    }
}
