package dev.micolash.jasm.core;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** How big a Network Brain is: on its own, or the heart of a 2×2×2 or 3×3×3 cube of chambers. */
public enum BrainSize implements StringRepresentable {
    SINGLE(1),
    CUBE_2(2),
    CUBE_3(3);

    private final int side;

    BrainSize(int side) {
        this.side = side;
    }

    public int side() {
        return side;
    }

    public static BrainSize ofSide(int side) {
        return switch (side) {
            case 2 -> CUBE_2;
            case 3 -> CUBE_3;
            default -> SINGLE;
        };
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
