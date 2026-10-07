package dev.micolash.jasm.config;

import com.mojang.serialization.Codec;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.registry.JasmItems;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.ModConfigSpec;

/** A part of JASM a world can turn off (or, for the Basic Bitling recipe, turn on). */
public enum Feature implements StringRepresentable {
    DECK_TO_DECK,
    BAYS,
    GENERATORS,
    BASIC_BITLING_RECIPE;

    public static final Codec<Feature> CODEC = StringRepresentable.fromEnum(Feature::values);
    public static final StreamCodec<ByteBuf, Feature> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Feature::ordinal);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    private ModConfigSpec.BooleanValue value() {
        return switch (this) {
            case DECK_TO_DECK -> JasmConfig.FEATURE_DECK_TO_DECK;
            case BAYS -> JasmConfig.FEATURE_BAYS;
            case GENERATORS -> JasmConfig.FEATURE_GENERATORS;
            case BASIC_BITLING_RECIPE -> JasmConfig.BASIC_BITLING_RECIPE;
        };
    }

    /** Whether this world has it; before any world has loaded the config, its default. */
    public boolean on() {
        return JasmConfig.SPEC.isLoaded() ? value().get() : value().getDefault();
    }

    /** The items that go away from JEI and the creative tab while it is off. */
    public List<ItemLike> items() {
        return switch (this) {
            case BAYS -> List.of(JasmItems.DEPLOYMENT_BAY.get(), JasmItems.DEMOLITION_BAY.get());
            case GENERATORS -> Arrays.stream(GeneratorTier.values()).<ItemLike>map(JasmItems::generator).toList();
            case DECK_TO_DECK, BASIC_BITLING_RECIPE -> List.of();
        };
    }
}
