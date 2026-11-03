package dev.micolash.jasm.autocraft;

/** How a job is doing, as the colour of the arrow and percent on the Advanced Crafting Server's front screen. */
public enum ServerScreenStatus {
    /** Nothing holding it up: green. */
    RUNNING,
    /** Paused, carries on by itself: yellow. */
    PAUSED,
    /** Stopped, or needs the player: red. */
    STOPPED;

    private static final ServerScreenStatus[] ALL = values();

    public static ServerScreenStatus of(int code) {
        return code > 0 && code < ALL.length ? ALL[code] : RUNNING;
    }

    public static ServerScreenStatus of(CraftingJob job) {
        if (job.phase() == CraftingJob.Phase.CANCELLING || job.ended() != PauseReason.NONE) {
            return STOPPED;
        }
        return switch (job.pause()) {
            case NONE -> RUNNING;
            case NO_POWER, WAITING_PLAYER, WAITING_SPACE, DECK_CHARGE, NETWORK_FULL, NO_MATERIAL, NO_ROOM, MACHINE_BUSY, MACHINE_IN_USE,
                    MACHINE_UNLOADED, MACHINE_OFF, PORT_OFF -> PAUSED;
            case NO_CARD, NO_NETWORK, NO_MACHINE, MACHINE_SIDE, NO_PORT, PORT_GONE, DIMENSION_UPGRADE -> STOPPED;
        };
    }
}
