package dev.micolash.jasm.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config. Only values that can never strand or truncate contents live here. */
public final class JasmConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("deck");
    }

    public static final ModConfigSpec.IntValue DECK_TRANSFER_BASE_COST = BUILDER
            .comment("FE charged per successful Deck grid operation")
            .defineInRange("transferBaseCost", 20, 0, 1_000_000);
    public static final ModConfigSpec.IntValue DECK_TRANSFER_PER_ITEM_COST = BUILDER
            .comment("FE charged per item moved by a Deck grid operation")
            .defineInRange("transferPerItemCost", 1, 0, 1_000_000);
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
        BUILDER.pop().push("generator");
    }

    public static final ModConfigSpec.IntValue GENERATOR_FE_PER_TICK = BUILDER
            .comment("Combustion Generator FE made per tick while burning")
            .defineInRange("fePerTick", 40, 0, 100_000);
    public static final ModConfigSpec.IntValue GENERATOR_PUSH_PER_FACE_PER_TICK = BUILDER
            .comment("Combustion Generator FE output limit per face per tick")
            .defineInRange("pushPerFacePerTick", 1_000, 0, 100_000);
    public static final ModConfigSpec.IntValue GENERATOR_CHARGE_PER_TICK = BUILDER
            .comment("Combustion Generator charging-slot FE per tick")
            .defineInRange("chargePerTick", 1_000, 0, 100_000);

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
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private JasmConfig() {}
}
