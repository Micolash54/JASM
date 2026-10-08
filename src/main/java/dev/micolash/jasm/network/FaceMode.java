package dev.micolash.jasm.network;

/** What one face of a machine lets through: nothing, things in, the machine's results out, or both. */
public enum FaceMode {
    NONE,
    INPUT,
    OUTPUT,
    BOTH;

    private static final FaceMode[] VALUES = values();

    public boolean in() {
        return this == INPUT || this == BOTH;
    }

    public boolean out() {
        return this == OUTPUT || this == BOTH;
    }

    public static FaceMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
    }
}
