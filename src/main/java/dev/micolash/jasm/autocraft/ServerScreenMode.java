package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** What the Advanced Crafting Server's front screen shows while a job runs. */
public enum ServerScreenMode {
    /** The step crafting now and the item made, big. */
    MINIMAL,
    /** Smaller icons with the amount, how far along, crafts running and a core bar. */
    INFO;

    private static final ServerScreenMode[] ALL = values();

    /** Saved by name; anything unknown reads as {@link #MINIMAL}. */
    public static final Codec<ServerScreenMode> CODEC = Codec.STRING.xmap(ServerScreenMode::named, mode -> mode.name().toLowerCase(Locale.ROOT));
    public static final StreamCodec<ByteBuf, ServerScreenMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(ServerScreenMode::of, Enum::ordinal);

    private static ServerScreenMode named(String name) {
        for (ServerScreenMode mode : ALL) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return MINIMAL;
    }

    /** The mode for a number from {@link #ordinal()}; anything unknown reads as {@link #MINIMAL}. */
    public static ServerScreenMode of(int code) {
        return code > 0 && code < ALL.length ? ALL[code] : MINIMAL;
    }

    public ServerScreenMode next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }
}
