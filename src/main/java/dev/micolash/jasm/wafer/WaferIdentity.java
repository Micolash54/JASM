package dev.micolash.jasm.wafer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.storage.StorageCodecs;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The only wafer data stored on the item: which record (the serial says where it is stored, the id confirms it is
 * the right one) and which instance generation. Serial 0 means unknown.
 */
public record WaferIdentity(UUID id, long serial, Stamp stamp) {
    public static final Codec<WaferIdentity> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(WaferIdentity::id),
            Codec.LONG.optionalFieldOf("serial", 0L).forGetter(WaferIdentity::serial),
            StorageCodecs.STAMP.fieldOf("stamp").forGetter(WaferIdentity::stamp))
            .apply(i, WaferIdentity::new));

    public static final StreamCodec<ByteBuf, WaferIdentity> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, WaferIdentity::id,
            ByteBufCodecs.VAR_LONG, WaferIdentity::serial,
            StorageCodecs.STAMP_STREAM, WaferIdentity::stamp,
            WaferIdentity::new);

    public WaferIdentity withStamp(Stamp newStamp) {
        return new WaferIdentity(id, serial, newStamp);
    }
}
