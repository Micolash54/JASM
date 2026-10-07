package dev.micolash.jasm.config;

/** Numbers that are the same in every world. They used to be settings; nobody needed to change them. */
public final class Tuning {
    /** Deck grid operations accepted per player per tick; the rest are dropped. */
    public static final int DECK_MAX_OPS_PER_TICK = 20;
    public static final int SEND_BASE_SECONDS = 5;
    public static final int SEND_DIMENSION_SECONDS = 30;
    public static final int SEND_MAX_SECONDS = 600;
    public static final int ARCHIVE_LINK_COST = 1_000;
    public static final int ARCHIVE_RECOVERY_COST = 10_000;
    /** Creative Battery output per face, and what its charging slot adds, each tick. */
    public static final int BATTERY_PUSH_PER_FACE_PER_TICK = 100_000;
    public static final int BATTERY_CHARGE_PER_TICK = 100_000;
    /** Ticks between the checks of wafers in player inventories. */
    public static final int WAFER_PASSIVE_CHECK_INTERVAL = 40;
    /** Largest encoded size, in bytes, of one item variant a wafer accepts. */
    public static final int WAFER_MAX_ITEM_DATA_BYTES = 32_768;
    /** Wafer records kept in memory, and the minutes one must go unused before it may leave. */
    public static final int WAFER_LOADED_RECORDS = 4_096;
    public static final int WAFER_IDLE_MINUTES = 10;
    public static final int POOL_SNAPSHOT_TICKS = 10;
    public static final int POOL_SCAN_SLOTS = 512;
    public static final int RULE_MIN_SECONDS = 10;
    public static final int RULE_RETRY_SECONDS = 5;
    public static final int QUENCH_TICKS = 40;
    public static final int FOUNDRY_TICKS_PER_CRYSTAL = 200;
    public static final int WORKSHOP_TICKS_PER_OPERATION = 200;
    public static final int WORKSHOP_TICKS_PER_BATCH = 1_200;
    public static final int BITLING_DRAIN_PER_CHIP = 2_000;

    /** Bitling Station: power a roaming Bitling uses each tick, the battery fraction it heads home at, and how far it roams. */
    public static final int STATION_ROAM_DRAIN = 5;
    public static final double STATION_RETURN_AT = 0.05;
    public static final int STATION_RADIUS_DEFAULT = 8;
    public static final int STATION_RADIUS_MAX = 16;
    public static final int STATION_HEALTH = 10;
    public static final int STATION_RESPAWN_SECONDS = 30;
    public static final int STATION_STUCK_SECONDS = 10;
    /** Ticks a wild Bitling fed a Data Crystal follows the player who fed it. */
    public static final int WILD_FOLLOW_TICKS = 600;

    private Tuning() {}
}
