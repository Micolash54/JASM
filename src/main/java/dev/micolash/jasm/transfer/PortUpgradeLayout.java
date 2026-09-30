package dev.micolash.jasm.transfer;

/** The four speed slots and the Access Port's separate power slot. */
public final class PortUpgradeLayout {
    public static final int MAIN_WIDTH = 234;
    public static final int SPEED_X = -78;
    public static final int Y = 160;
    public static final int PANEL_X = SPEED_X - 6;
    public static final int PANEL_Y = Y - 6;
    public static final int PANEL_WIDTH = 82;
    public static final int SPEED_PANEL_HEIGHT = 28;
    public static final int POWER_PANEL_Y = PANEL_Y + SPEED_PANEL_HEIGHT + 6;
    public static final int POWER_PANEL_SIZE = 28;
    public static final int POWER_PANEL_X = PANEL_X + PANEL_WIDTH - POWER_PANEL_SIZE;
    public static final int POWER_X = POWER_PANEL_X + 6;
    public static final int POWER_Y = POWER_PANEL_Y + 6;

    private PortUpgradeLayout() {}

    public static int x(int slot) { return SPEED_X + slot * 18; }
    public static int y(int slot) { return Y; }
}
