package dev.micolash.jasm.bay;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** The ports' three redstone modes, and one more: one action per pulse. */
public enum BayRedstone implements StringRepresentable {
    IGNORE("ignore"),
    POWERED("powered"),
    UNPOWERED("unpowered"),
    PULSE("pulse");

    public static final Codec<BayRedstone> CODEC = StringRepresentable.fromEnum(BayRedstone::values);
    public static final StreamCodec<ByteBuf, BayRedstone> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(BayRedstone::byId, BayRedstone::ordinal);
    private final String name;

    BayRedstone(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static BayRedstone byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : IGNORE;
    }

    public BayRedstone next() {
        return byId((ordinal() + 1) % values().length);
    }

    /** For the steady modes. Pulse mode is handled by the bay's clock. */
    public boolean allows(boolean powered) {
        return switch (this) {
            case IGNORE, PULSE -> true;
            case POWERED -> powered;
            case UNPOWERED -> !powered;
        };
    }
}
