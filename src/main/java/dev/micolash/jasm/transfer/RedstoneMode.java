package dev.micolash.jasm.transfer;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public enum RedstoneMode implements StringRepresentable {
    IGNORE("ignore"),
    POWERED("powered"),
    UNPOWERED("unpowered");

    public static final Codec<RedstoneMode> CODEC = StringRepresentable.fromEnum(RedstoneMode::values);
    public static final StreamCodec<ByteBuf, RedstoneMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(RedstoneMode::byId, RedstoneMode::ordinal);
    private final String name;

    RedstoneMode(String name) { this.name = name; }
    @Override
    public String getSerializedName() { return name; }
    public static RedstoneMode byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : IGNORE;
    }
    public RedstoneMode next() { return byId((ordinal() + 1) % values().length); }
    public boolean allows(boolean powered) {
        return this == IGNORE || (this == POWERED ? powered : !powered);
    }
}
