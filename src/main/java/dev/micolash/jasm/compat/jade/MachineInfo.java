package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftingJob;
import dev.micolash.jasm.autocraft.CraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/**
 * Crafting-network blocks: whose they are and whether they have power; for a Recipe Rack, how many cards it holds;
 * for a Crafting Server, what its job makes and how far along it is.
 */
public class MachineInfo implements StreamServerDataProvider<BlockAccessor, MachineInfo.Data> {
    public static final MachineInfo INSTANCE = new MachineInfo();

    /** {@code phase}: -1 no job, else the job's phase. {@code cards}: -1 when not a rack. */
    public record Data(String owner, boolean running, int cards, int phase, int progress, ItemStack target, long amount) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::owner,
                ByteBufCodecs.BOOL, Data::running,
                ByteBufCodecs.VAR_INT, Data::cards,
                ByteBufCodecs.VAR_INT, Data::phase,
                ByteBufCodecs.VAR_INT, Data::progress,
                ItemStack.OPTIONAL_STREAM_CODEC, Data::target,
                ByteBufCodecs.VAR_LONG, Data::amount,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof MachineBlockEntity machine)) {
            return null;
        }
        int cards = machine instanceof RecipeRackBlockEntity rack
                ? (int) java.util.stream.IntStream.range(0, rack.getContainerSize()).filter(i -> !rack.getItem(i).isEmpty()).count() : -1;
        CraftingJob job = machine instanceof CraftingServerBlockEntity server ? server.job() : null;
        return new Data(machine.ownerName(), machine.running(), cards, job == null ? -1 : job.phase().ordinal(),
                job == null ? 0 : Math.round(job.progress() * 100), job == null || job.target() == null ? ItemStack.EMPTY : job.target().create(),
                job == null ? 0 : job.amount());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("machine");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends MachineInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                if (!data.owner().isEmpty()) {
                    tooltip.add(Component.translatable("jade.jasm.archive.owner", data.owner()));
                }
                if (!data.running()) {
                    tooltip.add(Component.translatable("screen.jasm.machine.no_power"));
                }
                if (data.cards() >= 0) {
                    tooltip.add(Component.translatable("jade.jasm.rack.cards", data.cards()));
                }
                if (data.phase() >= 0 && !data.target().isEmpty()) {
                    tooltip.add(Component.translatable("jade.jasm.server.job", data.amount(), data.target().getHoverName(), data.progress()));
                } else if (data.phase() < 0 && data.cards() < 0 && accessor.getBlockEntity() instanceof CraftingServerBlockEntity) {
                    tooltip.add(Component.translatable("screen.jasm.server.idle"));
                }
            });
        }
    }
}
