package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which of a card's nine input slots are pinned, one bit per slot. A pinned slot takes only the card's own item, never
 * another item the recipe would also take there (the oak log, not any log). Saved as the list of pinned slots.
 */
public final class Pins {
    private Pins() {}

    public static final int NONE = 0;
    private static final int ALL = (1 << 9) - 1;

    public static final Codec<Integer> CODEC = Codec.intRange(0, 8).listOf().xmap(Pins::of, Pins::slots);
    public static final StreamCodec<ByteBuf, Integer> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(pins -> pins & ALL, pins -> pins & ALL);

    /** The pins for these slot numbers; numbers outside 0-8 are left out. */
    public static int of(List<Integer> slots) {
        int pins = NONE;
        for (int slot : slots) {
            pins = with(pins, slot, true);
        }
        return pins;
    }

    /** The pinned slot numbers, lowest first. */
    public static List<Integer> slots(int pins) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            if (has(pins, slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    public static boolean has(int pins, int slot) {
        return slot >= 0 && slot < 9 && (pins & (1 << slot)) != 0;
    }

    /** {@code pins} with {@code slot} pinned or not. */
    public static int with(int pins, int slot, boolean pinned) {
        if (slot < 0 || slot >= 9) {
            return pins & ALL;
        }
        return (pinned ? pins | (1 << slot) : pins & ~(1 << slot)) & ALL;
    }
}
