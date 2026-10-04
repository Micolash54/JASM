package dev.micolash.jasm.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config. Only values that can never strand or truncate contents live here. */
public final class JasmConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("deck");
    }

    public static final ModConfigSpec.IntValue DECK_MAX_OPS_PER_TICK = BUILDER
            .comment("Deck grid operations accepted per player per tick; excess requests are dropped")
            .defineInRange("maxOpsPerTick", 20, 1, 1_000);
    public static final ModConfigSpec.IntValue DECK_ENERGY_PER_ITEM = BUILDER
            .comment("FE a Deck uses for each item that goes into or out of its wafers")
            .defineInRange("energyPerItem", 1, 0, 1_000);
    public static final ModConfigSpec.IntValue SEND_BASE_SECONDS = BUILDER
            .comment("Deck to Deck: seconds every trip takes, however short")
            .defineInRange("sendBaseSeconds", 5, 0, 3_600);
    public static final ModConfigSpec.IntValue SEND_BLOCKS_PER_SECOND = BUILDER
            .comment("Deck to Deck: blocks a trip covers each second")
            .defineInRange("sendBlocksPerSecond", 64, 1, 100_000);
    public static final ModConfigSpec.IntValue SEND_DIMENSION_SECONDS = BUILDER
            .comment("Deck to Deck: extra seconds for a trip to another dimension")
            .defineInRange("sendDimensionSeconds", 30, 0, 3_600);
    public static final ModConfigSpec.IntValue SEND_MAX_SECONDS = BUILDER
            .comment("Deck to Deck: the longest any trip takes")
            .defineInRange("sendMaxSeconds", 180, 1, 86_400);

    static {
        BUILDER.pop().push("archive");
    }

    public static final ModConfigSpec.IntValue ARCHIVE_LINK_COST = BUILDER
            .comment("FE consumed by linking a wafer")
            .defineInRange("linkCost", 1_000, 0, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue ARCHIVE_RECOVERY_COST = BUILDER
            .comment("FE consumed by one recovery")
            .defineInRange("recoveryCost", 10_000, 0, Integer.MAX_VALUE);

    static {
        BUILDER.pop().push("battery");
    }

    public static final ModConfigSpec.IntValue BATTERY_PUSH_PER_FACE_PER_TICK = BUILDER
            .comment("Creative Battery FE output limit per face per tick")
            .defineInRange("pushPerFacePerTick", 100_000, 0, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue BATTERY_CHARGE_PER_TICK = BUILDER
            .comment("Creative Battery charging-slot FE per tick")
            .defineInRange("chargePerTick", 100_000, 0, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue ACCEPTOR_RATE = BUILDER
            .comment("Power Acceptor FE it takes per push and passes on per tick")
            .defineInRange("acceptorRate", 10_000, 1, 100_000_000);

    static {
        BUILDER.pop().push("wafer");
    }

    public static final ModConfigSpec.IntValue WAFER_PASSIVE_CHECK_INTERVAL = BUILDER
            .comment("Ticks between passive validity checks of wafers in player inventories")
            .defineInRange("passiveCheckInterval", 40, 1, 12_000);
    public static final ModConfigSpec.IntValue WAFER_MAX_ITEM_DATA_BYTES = BUILDER
            .comment("Largest encoded size in bytes of one distinct item variant a wafer accepts")
            .defineInRange("maxItemDataBytes", 32_768, 1_024, 1_048_576);
    public static final ModConfigSpec.IntValue WAFER_LOADED_RECORDS = BUILDER
            .comment("Wafer records kept in memory; beyond this, saved records nobody has used for a while are read again when needed")
            .defineInRange("loadedRecords", 4_096, 64, 1_000_000);
    public static final ModConfigSpec.IntValue WAFER_IDLE_MINUTES = BUILDER
            .comment("Minutes a wafer record must go unused before it may leave memory")
            .defineInRange("idleMinutes", 10, 0, 1_440);

    static {
        BUILDER.pop().push("autocrafting");
    }

    public static final ModConfigSpec.IntValue TERMINAL_DRAIN = BUILDER
            .comment("FE the Encoding Terminal uses each tick")
            .defineInRange("terminalDrain", 5, 0, 1_000_000);
    public static final ModConfigSpec.IntValue RACK_DRAIN = BUILDER
            .comment("FE a Recipe Rack uses each tick")
            .defineInRange("rackDrain", 2, 0, 1_000_000);
    public static final ModConfigSpec.IntValue SERVER_DRAIN = BUILDER
            .comment("FE a Crafting Server uses each tick on its own, busy or idle")
            .defineInRange("serverDrain", 20, 0, 1_000_000);
    public static final ModConfigSpec.IntValue PORT_DRAIN = BUILDER
            .comment("FE an Access Port uses each tick")
            .defineInRange("portDrain", 2, 0, 1_000_000);
    public static final ModConfigSpec.IntValue POOL_SNAPSHOT_TICKS = BUILDER
            .comment("Ticks a Storage Port chest listing is reused before the chests are read again")
            .defineInRange("poolSnapshotTicks", 10, 1, 200);
    public static final ModConfigSpec.IntValue POOL_SCAN_SLOTS = BUILDER
            .comment("Most slots read from one chest when listing a network's storage")
            .defineInRange("poolScanSlots", 512, 1, 100_000);
    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_BASIC = BUILDER
            .comment("FE each Basic Processor adds to its server's use per tick")
            .defineInRange("basicProcessorDrain", 10, 0, 1_000_000);
    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_ADVANCED = BUILDER
            .comment("FE each Advanced Processor adds to its server's use per tick")
            .defineInRange("advancedProcessorDrain", 30, 0, 1_000_000);
    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_ELITE = BUILDER
            .comment("FE each Elite Processor adds to its server's use per tick")
            .defineInRange("eliteProcessorDrain", 80, 0, 1_000_000);
    public static final ModConfigSpec.IntValue CRAFT_TICKS = BUILDER
            .comment("Ticks one craft takes on a Crafting Server, on every Processor tier")
            .defineInRange("craftTicks", 10, 1, 1_200);
    public static final ModConfigSpec.IntValue MAX_REQUEST = BUILDER
            .comment("Most items one crafting request may ask for")
            .defineInRange("maxRequest", 100_000, 1, 10_000_000);
    public static final ModConfigSpec.IntValue RULE_MIN_SECONDS = BUILDER
            .comment("Shortest timer a Crafting Deck rule may use, in seconds")
            .defineInRange("ruleMinSeconds", 10, 1, 86_400);
    public static final ModConfigSpec.IntValue RULE_RETRY_SECONDS = BUILDER
            .comment("Seconds a rule that couldn't start waits before trying again")
            .defineInRange("ruleRetrySeconds", 5, 1, 3_600);
    public static final ModConfigSpec.IntValue CABLE_RATE = BUILDER
            .comment("FE a Data Cable network moves into or out of each block it touches, per tick. Between two cables, the slower one sets the rate")
            .defineInRange("cableRate", 1_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue ADVANCED_CABLE_RATE = BUILDER
            .comment("Advanced Data Cable transfer rate, FE per tick")
            .defineInRange("advancedCableRate", 10_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue ELITE_CABLE_RATE = BUILDER
            .comment("Elite Data Cable transfer rate, FE per tick")
            .defineInRange("eliteCableRate", 50_000, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop().push("chips");
    }

    public static final ModConfigSpec.DoubleValue SEEDED_WEAR_CHANCE = BUILDER
            .comment("Chance each time a Data Crystal bud grows that its Seeded Amethyst wears down one stage")
            .defineInRange("seededWearChance", 0.12, 0.0, 1.0);
    public static final ModConfigSpec.IntValue RESONATOR_DRAIN = BUILDER
            .comment("FE a powered Crystal Resonator uses each tick")
            .defineInRange("resonatorDrain", 10, 0, 1_000_000);
    public static final ModConfigSpec.IntValue RESONATOR_INTERVAL = BUILDER
            .comment("Ticks between the extra growth attempts a Crystal Resonator gives each Seeded Amethyst it touches")
            .defineInRange("resonatorInterval", 600, 1, 72_000);
    public static final ModConfigSpec.IntValue QUENCH_TICKS = BUILDER
            .comment("Ticks an Unquenched chip must lie in water to become a chip")
            .defineInRange("quenchTicks", 40, 1, 12_000);
    public static final ModConfigSpec.IntValue FOUNDRY_CRYSTALS_PER_SEED = BUILDER
            .comment("Data Crystals, cut straight into Blank Chips, that the Crystal Foundry grows from one Crystal Seed")
            .defineInRange("foundryCrystalsPerSeed", 16, 1, 1_000);
    public static final ModConfigSpec.IntValue FOUNDRY_TICKS_PER_CRYSTAL = BUILDER
            .comment("Ticks the Crystal Foundry takes to grow and cut one crystal")
            .defineInRange("foundryTicksPerCrystal", 200, 1, 72_000);
    public static final ModConfigSpec.IntValue FOUNDRY_DRAIN = BUILDER
            .comment("FE the Crystal Foundry uses each tick while growing")
            .defineInRange("foundryDrain", 40, 0, 1_000_000);
    public static final ModConfigSpec.IntValue WORKSHOP_TICKS_PER_OPERATION = BUILDER
            .comment("Ticks one Chip Workshop operation takes in Single mode (one chip)")
            .defineInRange("workshopTicksPerOperation", 200, 1, 72_000);
    public static final ModConfigSpec.IntValue WORKSHOP_TICKS_PER_BATCH = BUILDER
            .comment("Ticks one Chip Workshop operation takes in Batch mode, however many chips (up to 8) it makes")
            .defineInRange("workshopTicksPerBatch", 1200, 1, 72_000);
    public static final ModConfigSpec.IntValue BITLING_DRAIN_PER_CHIP = BUILDER
            .comment("FE taken from the working critter for each chip it finishes, in Single and in Batch mode")
            .defineInRange("bitlingDrainPerChip", 2000, 0, 1_000_000);
    public static final ModConfigSpec.DoubleValue ODDS_OWN_TYPE_BITLING = BUILDER
            .comment("Chance a young typed Bitling makes its own chip type; the other two types share the rest")
            .defineInRange("oddsOwnTypeBitling", 0.60, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue ADVANCED_CHANCE_BASIC = BUILDER
            .comment("Chance a chip made by a Basic Bitling comes out Advanced")
            .defineInRange("advancedChanceBasic", 0.02, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue ADVANCED_CHANCE_BITLING = BUILDER
            .comment("Chance a chip made by a typed Bitling comes out Advanced")
            .defineInRange("advancedChanceBitling", 0.05, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue ADVANCED_CHANCE_NIBBLING = BUILDER
            .comment("Chance a chip made by a Nibbling comes out Advanced")
            .defineInRange("advancedChanceNibbling", 0.15, 0.0, 1.0);
    public static final ModConfigSpec.IntValue TRAINING_BITLING = BUILDER
            .comment("Chips a typed Bitling must make before it can evolve")
            .defineInRange("trainingBitling", 100, 1, 1_000_000);
    public static final ModConfigSpec.IntValue TRAINING_NIBBLING = BUILDER
            .comment("Chips a Nibbling must make before it can evolve")
            .defineInRange("trainingNibbling", 300, 1, 1_000_000);
    public static final ModConfigSpec.IntValue BATTERY_BASIC = BUILDER
            .comment("FE the battery of a Basic Bitling holds")
            .defineInRange("batteryBasic", 50_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue BATTERY_BITLING = BUILDER
            .comment("FE the battery of a typed Bitling holds")
            .defineInRange("batteryBitling", 100_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue BATTERY_NIBBLING = BUILDER
            .comment("FE the battery of a Nibbling holds")
            .defineInRange("batteryNibbling", 200_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue BATTERY_BYTELING = BUILDER
            .comment("FE the battery of a Byteling holds")
            .defineInRange("batteryByteling", 400_000, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop().push("bitling_station");
    }

    public static final ModConfigSpec.IntValue STATION_ROAM_DRAIN = BUILDER
            .comment("FE per tick a roaming Bitling uses from its battery while it is out and about")
            .defineInRange("roamDrain", 5, 0, 1_000_000);
    public static final ModConfigSpec.DoubleValue STATION_RETURN_AT = BUILDER
            .comment("Battery fraction at which a roaming Bitling heads home to recharge")
            .defineInRange("returnAt", 0.05, 0.0, 1.0);
    public static final ModConfigSpec.IntValue STATION_RADIUS_DEFAULT = BUILDER
            .comment("Roaming radius, in blocks, a new Bitling Station starts with")
            .defineInRange("radiusDefault", 8, 4, 16);
    public static final ModConfigSpec.IntValue STATION_RADIUS_MAX = BUILDER
            .comment("Largest roaming radius the station's slider allows")
            .defineInRange("radiusMax", 16, 4, 16);
    public static final ModConfigSpec.IntValue STATION_HEALTH = BUILDER
            .comment("Health of a roaming Bitling (2 per heart)")
            .defineInRange("health", 10, 1, 1_000);
    public static final ModConfigSpec.IntValue STATION_RESPAWN_SECONDS = BUILDER
            .comment("Seconds before a knocked-out Bitling pops out of its station again")
            .defineInRange("respawnSeconds", 30, 1, 3_600);
    public static final ModConfigSpec.IntValue STATION_STUCK_SECONDS = BUILDER
            .comment("Seconds a Bitling can be stuck on its way home before it teleports onto the station")
            .defineInRange("stuckSeconds", 10, 1, 600);

    static {
        BUILDER.pop().push("brain");
    }

    public static final ModConfigSpec.IntValue BRAIN_LIMIT_WITHOUT_BRAIN = BUILDER
            .comment("Machines a network may hold without a working Network Brain")
            .defineInRange("limitWithoutBrain", 4, 0, 4_096);
    public static final ModConfigSpec.IntValue BRAIN_MACHINE_LIMIT = BUILDER
            .comment("Machines a network may hold with a lone Network Brain")
            .defineInRange("machineLimit", 12, 0, 4_096);
    public static final ModConfigSpec.IntValue BRAIN_FLOOR_BONUS = BUILDER
            .comment("Machines each brain floor (a brain with 8 Network Chambers round it) adds")
            .defineInRange("floorBonus", 12, 0, 4_096);
    public static final ModConfigSpec.IntValue BRAIN_MAX_FLOORS = BUILDER
            .comment("Floors one brain tower may have. Floors stacked past this make a tower of their own")
            .defineInRange("maxFloors", 8, 1, 64);
    public static final ModConfigSpec.IntValue BRAIN_DRAIN_PER_FLOOR = BUILDER
            .comment("FE each Network Brain uses every tick, so each floor of a tower uses this much")
            .defineInRange("drainPerFloor", 8, 0, 100_000);

    static {
        BUILDER.pop().push("wild_bitling");
    }

    public static final ModConfigSpec.IntValue WILD_ATTRACT_INTERVAL = BUILDER
            .comment("Average ticks between tries of a growing Seeded Amethyst to draw a wild Bitling over")
            .defineInRange("attractInterval", 6_000, 200, 1_000_000);
    public static final ModConfigSpec.IntValue WILD_FOLLOW_TICKS = BUILDER
            .comment("Ticks a wild Bitling fed a Data Crystal follows the player who fed it")
            .defineInRange("followTicks", 600, 20, 72_000);
    public static final ModConfigSpec.IntValue WILD_FOLLOW_RANGE = BUILDER
            .comment("Blocks away a player can get before a wild Bitling stops following them")
            .defineInRange("followRange", 24, 4, 128);
    public static final ModConfigSpec.IntValue WILD_HEALTH = BUILDER
            .comment("Health of a wild Bitling (2 per heart)")
            .defineInRange("health", 10, 1, 100);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private JasmConfig() {}
}
