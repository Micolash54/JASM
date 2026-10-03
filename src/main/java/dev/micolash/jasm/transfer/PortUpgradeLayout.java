package dev.micolash.jasm.transfer;

/**
 * The ports' right-hand column, just past the main panel like the Deck's tabs: the side keys at the top, then a line,
 * then the four upgrade slots one above the other and the power slot under them.
 */
public final class PortUpgradeLayout {
    public static final int MAIN_WIDTH = 234;
    /** Left edge of the side keys. */
    public static final int KEY_X = MAIN_WIDTH;
    public static final int SLOT_X = KEY_X + 2;
    private static final int KEY_Y = 29;
    private static final int KEY_STEP = 21;

    private PortUpgradeLayout() {}

    /** Just under the last of {@code keys} side keys. */
    private static int keysBottom(int keys) {
        return KEY_Y + keys * KEY_STEP + 1;
    }

    /** Where the line between the keys and the slots runs. */
    public static int dividerY(int keys) {
        return keysBottom(keys) + 3;
    }

    public static int speedY(int keys, int slot) {
        return keysBottom(keys) + 10 + slot * 18;
    }

    public static int powerY(int keys) {
        return speedY(keys, PortOperations.UPGRADE_SLOTS) + 4;
    }

    public static int redstoneY(int keys) {
        return powerY(keys) + 23;
    }

    /** The column as a frame rectangle {x, y, width, height}, tucked under the main panel's edge. */
    public static int[] column(int keys) {
        return column(keys, false);
    }

    public static int[] column(int keys, boolean redstone) {
        int bottom = redstone ? redstoneY(keys) + 22 : powerY(keys) + 17;
        return new int[]{KEY_X - 14, KEY_Y - 4, 38, bottom + 5 - (KEY_Y - 4)};
    }
}
