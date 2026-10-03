package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.CraftingJob;
import dev.micolash.jasm.autocraft.CraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkLimit;
import dev.micolash.jasm.network.Networks;
import java.util.stream.IntStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/**
 * Crafting-network blocks: whose they are and whether they have power; for a Recipe Rack, how many cards it holds;
 * for a Crafting Server, what its job makes and how far along it is; for an Access Port, the machine it faces and
 * whether a job is using it.
 */
public class MachineInfo implements StreamServerDataProvider<BlockAccessor, MachineInfo.Data> {
    public static final MachineInfo INSTANCE = new MachineInfo();

    /**
     * {@code phase}: -1 no job, else the job's phase. {@code cards}: -1 when not a rack. {@code machine}: empty when not a port.
     * {@code fullCount} and {@code fullLimit}: the network's machines and limit while it is full, both 0 otherwise.
     */
    public record Data(String owner, boolean running, int cards, int phase, int progress, ItemStack target, long amount, String machine,
            boolean inUse, int fullCount, int fullLimit) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::owner,
                ByteBufCodecs.BOOL, Data::running,
                ByteBufCodecs.VAR_INT, Data::cards,
                ByteBufCodecs.VAR_INT, Data::phase,
                ByteBufCodecs.VAR_INT, Data::progress,
                ItemStack.OPTIONAL_STREAM_CODEC, Data::target,
                ByteBufCodecs.VAR_LONG, Data::amount,
                ByteBufCodecs.STRING_UTF8, Data::machine,
                ByteBufCodecs.BOOL, Data::inUse,
                ByteBufCodecs.VAR_INT, Data::fullCount,
                ByteBufCodecs.VAR_INT, Data::fullLimit,
                Data::new);
    }

    @Override
    public @Nullable Data streamData(BlockAccessor accessor) {
        MachineBlockEntity machine = machine(accessor);
        if (machine == null) return null;
        int cards = machine instanceof RecipeRackBlockEntity rack
                ? (int) IntStream.range(0, rack.getContainerSize()).filter(i -> !rack.getItem(i).isEmpty()).count()
                : -1;
        CraftingJob job = machine instanceof CraftingServerBlockEntity server ? server.job() : null;
        NetworkLimit.State full = null;
        if (machine.stopped() && machine.getLevel() instanceof ServerLevel level
                && Networks.at(level, machine.getBlockPos()) instanceof CableNetwork network) {
            full = network.limitState();
        }
        return new Data(machine.ownerName(), machine.running(), cards, job == null ? -1 : job.phase().ordinal(),
                job == null ? 0 : Math.round(job.progress() * 100), job == null || job.target() == null ? ItemStack.EMPTY : job.target().create(),
                job == null ? 0 : job.amount(), machine instanceof AccessPortBlockEntity port ? port.machineNames().getString() : "",
                machine instanceof AccessPortBlockEntity port && port.locked(), full == null ? 0 : full.count(), full == null ? 0 : full.limit());
    }

    static @Nullable MachineBlockEntity machine(BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof MachineBlockEntity machine) return machine;
        if (accessor.getBlockEntity() instanceof DataCableBlockEntity cable) {
            var side = DataCableBlock.hitPort(cable, accessor.getHitResult().getLocation());
            if (side != null) return cable.port(side);
        }
        return null;
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
                if (data.fullLimit() > 0) {
                    tooltip.add(Component.translatable("screen.jasm.machine.network_full", data.fullCount(), data.fullLimit()));
                } else
                    if (!data.running() && !(accessor.getBlockEntity() instanceof NetworkBrainBlockEntity)
                            && !(accessor.getBlockEntity() instanceof NetworkChamberBlockEntity)) {
                                // A brain's own status line says when it has no power, and a chamber never runs by itself.
                                tooltip.add(Component.translatable("screen.jasm.machine.no_power"));
                            }
                if (!data.machine().isEmpty()) {
                    tooltip.add(Component.translatable("screen.jasm.port.machine", data.machine()));
                    tooltip.add(Component.translatable(data.inUse() ? "screen.jasm.port.in_use" : "screen.jasm.port.idle"));
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
