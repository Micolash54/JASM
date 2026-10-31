package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * What Access Ports mined in early test builds carried on their item. Nothing of it is used any more; it is only read
 * so those items still load.
 */
public record PortMemory() {
    public static final PortMemory EMPTY = new PortMemory();

    // reads whatever was saved, keeps none of it
    public static final Codec<PortMemory> CODEC = MapCodec.unit(EMPTY).codec();

    public static final StreamCodec<RegistryFriendlyByteBuf, PortMemory> STREAM_CODEC = StreamCodec.unit(EMPTY);
}
