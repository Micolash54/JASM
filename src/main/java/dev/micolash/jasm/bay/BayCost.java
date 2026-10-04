package dev.micolash.jasm.bay;

/** What breaking a block costs a Demolition Bay. */
public final class BayCost {
    private BayCost() {}

    /**
     * 1 + hardness + items dropped, times 8 × the levels of all enchantments other than Efficiency and Unbreaking added
     * up (Fortune III: × 24). Each Efficiency level takes 15% off.
     */
    public static double breakPoints(float hardness, int drops, int efficiency, int otherLevels) {
        double points = 1 + hardness + drops;
        if (otherLevels > 0) points *= 8 * otherLevels;
        return points * Math.pow(0.85, efficiency);
    }

    public static int fe(double points, int perPoint) {
        return perPoint <= 0 ? 0 : (int) Math.ceil(points * perPoint);
    }

    /** Unbreaking: {@code roll} is a random number from 0 to the level; only 0 pays. */
    public static boolean pays(int unbreaking, int roll) {
        return unbreaking <= 0 || roll == 0;
    }
}
