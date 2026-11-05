package dev.micolash.jasm.bay;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Whether a Deployment Bay places what it holds, throws it out, or right-clicks what is in front with the first grid
 * slot's stack in hand.
 */
public enum DeployMode implements StringRepresentable {
    PLACE("place"),
    DROP("drop"),
    INTERACT("interact");

    private static final DeployMode[] VALUES = values();
    public static final Codec<DeployMode> CODEC = StringRepresentable.fromEnum(DeployMode::values);
    public static final StreamCodec<ByteBuf, DeployMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(DeployMode::byId, DeployMode::ordinal);
    private final String name;

    DeployMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static DeployMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : PLACE;
    }

    /** The next mode, in the order the panel shows them. */
    public DeployMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }
}
