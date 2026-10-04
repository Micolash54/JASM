package dev.micolash.jasm.wafer;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** What a wafer stores. A wafer holds one kind and never mixes them. */
public enum WaferKind implements StringRepresentable {
    ITEM,
    FLUID;

    public static final Codec<WaferKind> CODEC = StringRepresentable.fromEnum(WaferKind::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
