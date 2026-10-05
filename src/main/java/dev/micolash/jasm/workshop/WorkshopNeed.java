package dev.micolash.jasm.workshop;

import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import java.util.Optional;

/**
 * Why a Workshop with a critter makes nothing, as one small number for the screen: nothing to say, the grid matches
 * no recipe, or the recipe in the grid needs a different critter (which kind, which stage).
 */
public final class WorkshopNeed {
    public static final int NONE = 0;
    public static final int NO_RECIPE = 1;
    private static final int FIRST_CRITTER = 2;
    private static final int STAGES = BitlingStage.values().length;

    private WorkshopNeed() {}

    /** The critter {@code recipe} asks for. Kind 0 means any kind; a Basic Bitling is never asked for. */
    public static int of(WorkshopRecipe recipe) {
        int kind = recipe.kind().map(Enum::ordinal).orElse(0);
        return FIRST_CRITTER + kind * STAGES + recipe.stage().ordinal();
    }

    public static boolean isCritter(int need) {
        return need >= FIRST_CRITTER;
    }

    public static Optional<BitlingKind> kind(int need) {
        int kind = (need - FIRST_CRITTER) / STAGES;
        return kind == 0 ? Optional.empty() : Optional.of(BitlingKind.values()[kind]);
    }

    public static BitlingStage stage(int need) {
        return BitlingStage.values()[(need - FIRST_CRITTER) % STAGES];
    }
}
