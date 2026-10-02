package dev.micolash.jasm.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A brain's level (1 to 10) and the points it has towards the next one. */
public record BrainProgress(int level, long points) {
    public static final BrainProgress START = new BrainProgress(1, 0);

    public static final Codec<BrainProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("level").forGetter(BrainProgress::level),
                    Codec.LONG.fieldOf("points").forGetter(BrainProgress::points))
            .apply(i, BrainProgress::new));

    public static final StreamCodec<ByteBuf, BrainProgress> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BrainProgress::level,
            ByteBufCodecs.VAR_LONG, BrainProgress::points,
            BrainProgress::new);

    public BrainProgress {
        level = Math.clamp(level, 1, BrainBalance.MAX_LEVEL);
        points = Math.max(0, points);
    }
}
