package dev.micolash.jasm.bay;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** Whether a Deployment Bay places what it holds or throws it out. */
public enum DeployMode implements StringRepresentable {
    PLACE("place"),
    DROP("drop");

    public static final Codec<DeployMode> CODEC = StringRepresentable.fromEnum(DeployMode::values);
    public static final StreamCodec<ByteBuf, DeployMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(i -> i == 1 ? DROP : PLACE, DeployMode::ordinal);
    private final String name;

    DeployMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public DeployMode toggle() {
        return this == PLACE ? DROP : PLACE;
    }
}
