package dev.micolash.jasm.acceptor;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.storage.ValueInput;

/** Which way a Power Acceptor moves power. A saved acceptor with no mode, or the old two-way one, takes power in. */
public enum AcceptorMode implements StringRepresentable {
    /** Takes power from other mods' blocks into the network. */
    INPUT("input"),
    /** Gives battery power to other mods' blocks. */
    OUTPUT("output");

    public static final Codec<AcceptorMode> CODEC = StringRepresentable.fromEnum(AcceptorMode::values);
    private final String name;

    AcceptorMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static AcceptorMode byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : INPUT;
    }

    /** The saved mode. Read as plain text, so an acceptor saved with a mode that is gone loads quietly. */
    static AcceptorMode load(ValueInput input) {
        return OUTPUT.name.equals(input.getStringOr("mode", INPUT.name)) ? OUTPUT : INPUT;
    }
}
