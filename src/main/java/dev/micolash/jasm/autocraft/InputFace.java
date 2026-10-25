package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * Which face of a machine an ingredient goes in through. Default is the face the Access Port touches, as always; the
 * others are compass directions plus top and bottom, the same whichever way the machine is turned.
 */
public enum InputFace implements StringRepresentable {
    DEFAULT("default", 'D', 0xFF7F849C, null),
    TOP("top", 'T', 0xFFF9E2AF, Direction.UP),
    BOTTOM("bottom", 'B', 0xFFFAB387, Direction.DOWN),
    NORTH("north", 'N', 0xFF89B4FA, Direction.NORTH),
    SOUTH("south", 'S', 0xFFF38BA8, Direction.SOUTH),
    EAST("east", 'E', 0xFFA6E3A1, Direction.EAST),
    WEST("west", 'W', 0xFFCBA6F7, Direction.WEST);

    public static final Codec<InputFace> CODEC = StringRepresentable.fromEnum(InputFace::values);
    public static final StreamCodec<ByteBuf, InputFace> STREAM_CODEC = ByteBufCodecs.idMapper(InputFace::byId, InputFace::ordinal);
    /** Nine slots, all Default: what every card without faces has. */
    public static final List<InputFace> NONE = Collections.nCopies(ProcessingCard.INPUTS, DEFAULT);

    private final String name;
    private final char letter;
    private final int color;
    private final @Nullable Direction direction;

    InputFace(String name, char letter, int color, @Nullable Direction direction) {
        this.name = name;
        this.letter = letter;
        this.color = color;
        this.direction = direction;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** The letter on its key and on a painted slot. */
    public char letter() {
        return letter;
    }

    /** Its colour on screen, as 0xAARRGGBB. */
    public int color() {
        return color;
    }

    /** The machine's face it names, or null for Default (the face the Access Port touches). */
    public @Nullable Direction direction() {
        return direction;
    }

    public String translationKey() {
        return "screen.jasm.face." + name;
    }

    public static InputFace byId(int id) {
        InputFace[] all = values();
        return id >= 0 && id < all.length ? all[id] : DEFAULT;
    }

    /** {@code faces} as exactly nine entries, missing ones Default. */
    public static List<InputFace> padded(@Nullable List<InputFace> faces) {
        if (faces == null || faces.isEmpty()) {
            return NONE;
        }
        List<InputFace> out = new ArrayList<>(ProcessingCard.INPUTS);
        for (int i = 0; i < ProcessingCard.INPUTS; i++) {
            InputFace face = i < faces.size() ? faces.get(i) : null;
            out.add(face == null ? DEFAULT : face);
        }
        return List.copyOf(out);
    }
}
