package dev.micolash.jasm.transfer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.storage.WaferSettings;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record TransferFilters(WaferSettings input, WaferSettings output) {
    public static final TransferFilters DEFAULT = new TransferFilters(WaferSettings.DEFAULT, WaferSettings.DEFAULT);
    public static final Codec<TransferFilters> CODEC = RecordCodecBuilder.create(i -> i.group(
            WaferSettings.CODEC.optionalFieldOf("input", WaferSettings.DEFAULT).forGetter(TransferFilters::input),
            WaferSettings.CODEC.optionalFieldOf("output", WaferSettings.DEFAULT).forGetter(TransferFilters::output)).apply(i, TransferFilters::new));
    public static final StreamCodec<ByteBuf, TransferFilters> STREAM_CODEC = StreamCodec.composite(
            WaferSettings.STREAM_CODEC, TransferFilters::input, WaferSettings.STREAM_CODEC, TransferFilters::output, TransferFilters::new);
}
