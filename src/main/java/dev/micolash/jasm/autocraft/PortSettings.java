package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.transfer.RedstoneMode;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;

/**
 * What a mined or wrenched Access Port keeps on its item and takes back when placed again: the kind of machine it
 * serves, the processing recipes it serves, Blocking Mode and its Redstone mode. Its name is the item's own name.
 */
public record PortSettings(Optional<Block> machine, List<ProcessingCard> recipes, boolean blocking, RedstoneMode redstone) {
    public static final PortSettings NONE = new PortSettings(Optional.empty(), List.of(), false, RedstoneMode.IGNORE);

    public static final Codec<PortSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("machine").forGetter(PortSettings::machine),
            ProcessingCard.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(PortSettings::recipes),
            Codec.BOOL.optionalFieldOf("blocking", false).forGetter(PortSettings::blocking),
            RedstoneMode.CODEC.optionalFieldOf("redstone", RedstoneMode.IGNORE).forGetter(PortSettings::redstone))
            .apply(i, PortSettings::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.BLOCK)), PortSettings::machine,
            ProcessingCard.STREAM_CODEC.apply(ByteBufCodecs.list()), PortSettings::recipes,
            ByteBufCodecs.BOOL, PortSettings::blocking,
            RedstoneMode.STREAM_CODEC, PortSettings::redstone,
            PortSettings::new);

    public PortSettings {
        recipes = recipes.stream().map(ProcessingCard::clean).distinct().toList();
    }

    public boolean isDefault() {
        return equals(NONE);
    }
}
