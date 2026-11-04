package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.transfer.RedstoneMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;

/**
 * What a mined or wrenched Access Port keeps on its item and takes back when placed again: the kinds of machine it
 * serves (the first in {@code machine}, any others in {@code moreMachines}), the processing recipes it serves, Blocking
 * Mode, its Redstone mode and whether it is Passive. Its name is the item's own name. Items from before several machines
 * or Passive have neither, and come back Active with their one kind.
 */
public record PortSettings(Optional<Block> machine, List<ProcessingCard> recipes, boolean blocking, RedstoneMode redstone,
        List<Block> moreMachines, boolean passive) {
    public static final PortSettings NONE = new PortSettings(Optional.empty(), List.of(), false, RedstoneMode.IGNORE);

    public static final Codec<PortSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("machine").forGetter(PortSettings::machine),
            ProcessingCard.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(PortSettings::recipes),
            Codec.BOOL.optionalFieldOf("blocking", false).forGetter(PortSettings::blocking),
            RedstoneMode.CODEC.optionalFieldOf("redstone", RedstoneMode.IGNORE).forGetter(PortSettings::redstone),
            BuiltInRegistries.BLOCK.byNameCodec().listOf().optionalFieldOf("more_machines", List.of()).forGetter(PortSettings::moreMachines),
            Codec.BOOL.optionalFieldOf("passive", false).forGetter(PortSettings::passive))
            .apply(i, PortSettings::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.BLOCK)), PortSettings::machine,
            ProcessingCard.STREAM_CODEC.apply(ByteBufCodecs.list()), PortSettings::recipes,
            ByteBufCodecs.BOOL, PortSettings::blocking,
            RedstoneMode.STREAM_CODEC, PortSettings::redstone,
            ByteBufCodecs.registry(Registries.BLOCK).apply(ByteBufCodecs.list()), PortSettings::moreMachines,
            ByteBufCodecs.BOOL, PortSettings::passive,
            PortSettings::new);

    public PortSettings {
        recipes = recipes.stream().map(ProcessingCard::clean).distinct().toList();
        moreMachines = machine.isEmpty() ? List.of() : moreMachines.stream().distinct().filter(b -> b != machine.get()).toList();
    }

    /** One kind of machine, Active: as the port kept it before it could serve several kinds or be Passive. */
    public PortSettings(Optional<Block> machine, List<ProcessingCard> recipes, boolean blocking, RedstoneMode redstone) {
        this(machine, recipes, blocking, redstone, List.of(), false);
    }

    /** Every kind of machine kept, the first one first. */
    public List<Block> machines() {
        List<Block> all = new ArrayList<>();
        machine.ifPresent(all::add);
        all.addAll(moreMachines);
        return all;
    }

    public boolean isDefault() {
        return equals(NONE);
    }
}
