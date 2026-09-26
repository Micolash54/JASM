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

    static {
        BUILDER.pop().push("wafer");
    }

    public static final ModConfigSpec.IntValue WAFER_PASSIVE_CHECK_INTERVAL = BUILDER
            .comment("Ticks between passive validity checks of wafers in player inventories")
            .defineInRange("passiveCheckInterval", 40, 1, 12_000);
    public static final ModConfigSpec.IntValue WAFER_MAX_ITEM_DATA_BYTES = BUILDER
            .comment("Largest encoded size in bytes of one distinct item variant a wafer accepts")
            .defineInRange("maxItemDataBytes", 32_768, 1_024, 1_048_576);

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
            .comment("FE a Data Cable network moves into or out of each block it touches, per tick")
            .defineInRange("cableRate", 1_000, 1, Integer.MAX_VALUE);
    public static final ModConfigSpec.IntValue CABLE_BUFFER = BUILDER
            .comment("FE each Data Cable holds while passing it on")
            .defineInRange("cableBuffer", 1_000, 1, 1_000_000);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private JasmConfig() {}
}
