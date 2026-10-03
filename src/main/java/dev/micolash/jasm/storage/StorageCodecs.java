package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Minecraft codecs for pure {@code core} types (core itself stays free of Minecraft imports). */
public final class StorageCodecs {
    public static final Codec<Stamp> STAMP = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("epoch").forGetter(Stamp::epoch),
            Codec.LONG.fieldOf("counter").forGetter(Stamp::counter))
            .apply(i, Stamp::new));

    public static final StreamCodec<ByteBuf, Stamp> STAMP_STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Stamp::epoch,
            ByteBufCodecs.VAR_LONG, Stamp::counter,
            Stamp::new);

    private StorageCodecs() {}
}
