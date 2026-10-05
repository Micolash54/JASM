package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.workshop.BitlingItem;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
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

/** The critter in a Chip Workshop: which one, how far its training is, and whether it is napping. */
public class WorkshopInfo implements StreamServerDataProvider<BlockAccessor, WorkshopInfo.Data> {
    public static final WorkshopInfo INSTANCE = new WorkshopInfo();

    /** {@code training}: percent, or -1 for a critter that doesn't train. {@code making} is what a recipe is building, if one is. */
    public record Data(ItemStack critter, int training, boolean napping, ItemStack making, int percent) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, Data::critter,
                ByteBufCodecs.VAR_INT, Data::training,
                ByteBufCodecs.BOOL, Data::napping,
                ItemStack.OPTIONAL_STREAM_CODEC, Data::making,
                ByteBufCodecs.VAR_INT, Data::percent,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof ChipWorkshopBlockEntity workshop)) {
            return null;
        }
        ItemStack critter = workshop.critterStack();
        BitlingItem bitling = workshop.critter();
        int required = bitling == null ? 0 : bitling.trainingRequired();
        int training = required > 0 ? Math.min(100, BitlingItem.trained(critter) * 100 / required) : -1;
        return new Data(critter.copyWithCount(1), training, workshop.napping(), workshop.making().copy(), workshop.progressPercent());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("chip_workshop");
    }

    /** Draws the lines. Jade wants this apart from the part that gathers the data on the server. */
    public static class Client extends WorkshopInfo implements IBlockComponentProvider {
        public static final Client INSTANCE = new Client();

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            decodeFromData(accessor).ifPresent(data -> {
                if (data.critter().isEmpty()) {
                    tooltip.add(Component.translatable("screen.jasm.workshop.no_critter"));
                    return;
                }
                tooltip.add(data.critter().getHoverName());
                if (data.training() >= 0) {
                    tooltip.add(Component.translatable("jade.jasm.workshop.training", data.training()));
                }
                if (!data.making().isEmpty()) {
                    tooltip.add(Component.translatable("jade.jasm.workshop.making", data.making().getHoverName(), data.percent()));
                }
                if (data.napping()) {
                    tooltip.add(Component.translatable("jade.jasm.workshop.napping"));
                }
            });
        }
    }
}
