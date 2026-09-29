package dev.micolash.jasm.workshop;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** What the Chip Workshop's status light shows. */
public enum WorkshopStatus implements StringRepresentable {
    IDLE,
    WORKING,
    NAPPING;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
