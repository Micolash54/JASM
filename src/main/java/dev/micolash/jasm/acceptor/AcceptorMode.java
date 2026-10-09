package dev.micolash.jasm.acceptor;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Which way a Power Acceptor moves power. A saved acceptor with no mode is an old one: it only takes power in. */
public enum AcceptorMode implements StringRepresentable {
    INPUT("input"),
    OUTPUT("output"),
    BOTH("both");

    public static final Codec<AcceptorMode> CODEC = StringRepresentable.fromEnum(AcceptorMode::values);
    private final String name;

    AcceptorMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Takes power from other mods' blocks into the network. */
    public boolean in() {
        return this != OUTPUT;
    }

    /** Gives network power to other mods' blocks. */
    public boolean out() {
        return this != INPUT;
    }

    public static AcceptorMode byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : INPUT;
    }
}
