package dev.micolash.jasm.core;

/** A typed Bitling or Nibbling learns from every chip it makes; a full bar lets it evolve. */
public final class Training {
    private Training() {}

    /** Chips needed to fill the bar; 0 for critters that never train (Basic Bitlings and Bytelings). */
    public static int required(BitlingKind kind, BitlingStage stage, ChipBalance balance) {
        if (kind.chipType() == null) {
            return 0;
        }
        return switch (stage) {
            case BITLING -> balance.trainingBitling();
            case NIBBLING -> balance.trainingNibbling();
            case BYTELING -> 0;
        };
    }

    public static boolean full(int trained, int required) {
        return required > 0 && trained >= required;
    }

    /** The bar after {@code made} more chips. It stops at full. */
    public static int add(int trained, int made, int required) {
        return Math.min(required, trained + made);
    }
}
