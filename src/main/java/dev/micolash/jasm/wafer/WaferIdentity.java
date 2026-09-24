package dev.micolash.jasm.wafer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.ledger.LedgerCodecs;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/** The only wafer data stored on the item: which ledger record, and which instance generation. */
public record WaferIdentity(UUID id, Stamp stamp) {
    public static final Codec<WaferIdentity> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(WaferIdentity::id),
                    LedgerCodecs.STAMP.fieldOf("stamp").forGetter(WaferIdentity::stamp))
            .apply(i, WaferIdentity::new));

    public static final StreamCodec<ByteBuf, WaferIdentity> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, WaferIdentity::id,
            LedgerCodecs.STAMP_STREAM, WaferIdentity::stamp,
            WaferIdentity::new);
}
