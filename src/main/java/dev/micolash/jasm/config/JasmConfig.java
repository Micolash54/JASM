package dev.micolash.jasm.config;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.deck.DeckTier;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * World rules: one copy per world, sent to every player who joins. Values that change slot counts or what exists apply
 * after the world reloads, and lowering them never deletes anything (a Deck keeps extra wafers to be taken out).
 */
public final class JasmConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Preset> PRESET = BUILDER
            .comment("Pick relaxed, standard or hardcore to fill in many of the numbers below at once. The numbers stay changeable one by one afterwards, and picking standard sets them all back. Deck slots apply after the world reloads")
            .defineEnum("preset", Preset.STANDARD);

    static {
        BUILDER.push("deck");
    }

    public static final ModConfigSpec.IntValue DECK_ENERGY_PER_ITEM = BUILDER
            .comment("FE a Deck uses for each item that goes into or out of its wafers")
            .defineInRange("energyPerItem", 1, 0, 1_000);

    public static final ModConfigSpec.IntValue SEND_BLOCKS_PER_SECOND = BUILDER
            .comment("Deck to Deck: blocks a trip covers each second")
            .defineInRange("sendBlocksPerSecond", 64, 1, 100_000);

    static {
        BUILDER.push("slots");
    }

    public static final ModConfigSpec.IntValue DECK_SLOTS_STARTER = BUILDER
            .worldRestart()
            .comment("Wafer slots in a Starter Deck. Lowering it never deletes a wafer: extra ones wait in the Deck to be taken out")
            .defineInRange("starter", 1, 1, 1_000);

    public static final ModConfigSpec.IntValue DECK_SLOTS_BASIC = BUILDER
            .worldRestart()
            .comment("Wafer slots in a Basic Deck")
            .defineInRange("basic", 3, 1, 1_000);

    public static final ModConfigSpec.IntValue DECK_SLOTS_ADVANCED = BUILDER
            .worldRestart()
            .comment("Wafer slots in an Advanced Deck")
            .defineInRange("advanced", 6, 1, 1_000);

    public static final ModConfigSpec.IntValue DECK_SLOTS_ELITE = BUILDER
            .worldRestart()
            .comment("Wafer slots in an Elite Deck")
            .defineInRange("elite", 12, 1, 1_000);

    public static final ModConfigSpec.IntValue DECK_SLOTS_ULTIMATE = BUILDER
            .worldRestart()
            .comment("Wafer slots in an Ultimate Deck. Past 24, the Deck's wafer panel gets pages")
            .defineInRange("ultimate", 24, 1, 1_000);

    static {
        BUILDER.pop().pop().push("power");
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

    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_BASIC = BUILDER
            .comment("FE each Basic Processor adds to its server's use per tick")
            .defineInRange("basicProcessorDrain", 10, 0, 1_000_000);

    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_ADVANCED = BUILDER
            .comment("FE each Advanced Processor adds to its server's use per tick")
            .defineInRange("advancedProcessorDrain", 30, 0, 1_000_000);

    public static final ModConfigSpec.IntValue PROCESSOR_DRAIN_ELITE = BUILDER
            .comment("FE each Elite Processor adds to its server's use per tick")
            .defineInRange("eliteProcessorDrain", 80, 0, 1_000_000);

    public static final ModConfigSpec.IntValue RESONATOR_DRAIN = BUILDER
            .comment("FE a powered Crystal Resonator uses each tick")
            .defineInRange("resonatorDrain", 10, 0, 1_000_000);

    public static final ModConfigSpec.IntValue FOUNDRY_DRAIN = BUILDER
            .comment("FE the Crystal Foundry uses each tick while growing")
            .defineInRange("foundryDrain", 40, 0, 1_000_000);

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

    public static final ModConfigSpec.IntValue BATTERY_MAX_BLOCKS = BUILDER
            .comment("Blocks one Battery may have. A block that would join up a bigger one can't be placed. A battery already bigger keeps working and all its power, it just can't grow")
            .defineInRange("batteryMaxBlocks", 100, 1, 4_096);

    static {
        BUILDER.pop().push("autocrafting");
    }

    public static final ModConfigSpec.IntValue CRAFT_TICKS = BUILDER
            .comment("Ticks one craft takes on a Crafting Server, on every Processor tier")
            .defineInRange("craftTicks", 10, 1, 1_200);

    public static final ModConfigSpec.IntValue MAX_REQUEST = BUILDER
            .comment("Most items one crafting request may ask for")
            .defineInRange("maxRequest", 100_000, 1, 10_000_000);

    static {
        BUILDER.pop().push("chips");
    }

    public static final ModConfigSpec.DoubleValue SEEDED_WEAR_CHANCE = BUILDER
            .comment("Chance each time a Data Crystal bud grows that its Seeded Amethyst wears down one stage")
            .defineInRange("seededWearChance", 0.12, 0.0, 1.0);

    public static final ModConfigSpec.IntValue RESONATOR_INTERVAL = BUILDER
            .comment("Ticks between the extra growth attempts a Crystal Resonator gives each plant or budding block it touches")
            .defineInRange("resonatorInterval", 15, 1, 72_000);

    public static final ModConfigSpec.DoubleValue RESONATOR_WEAR_CHANCE = BUILDER
            .comment("Chance each time a Crystal Resonator's extra growth makes a bud grow that the Seeded Amethyst wears down one stage")
            .defineInRange("resonatorWearChance", 0.06, 0.0, 1.0);

    public static final ModConfigSpec.IntValue FOUNDRY_CRYSTALS_PER_SEED = BUILDER
            .comment("Data Crystals, cut straight into Blank Chips, that the Crystal Foundry grows from one Crystal Seed")
            .defineInRange("foundryCrystalsPerSeed", 16, 1, 1_000);

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

    static {
        BUILDER.pop().push("brain");
    }

    public static final ModConfigSpec.IntValue BRAIN_LIMIT_WITHOUT_BRAIN = BUILDER
            .comment("Machines a network may hold without a working Network Brain")
            .defineInRange("limitWithoutBrain", 4, 0, 4_096);

    public static final ModConfigSpec.IntValue BRAIN_MAX_FLOORS = BUILDER
            .comment("Floors one brain tower may have. Floors stacked past this make a tower of their own")
            .defineInRange("maxFloors", 8, 1, 64);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> BRAIN_MACHINES = BUILDER
            .comment("Machines a network may hold, for a lone Network Brain and then for a tower of 1, 2, 3... floors (a floor is a brain with 8 Network Chambers round it). A taller tower uses the last entry")
            .defineList("machines", BrainBalance.DEFAULT_MACHINES, () -> 0, value -> value instanceof Integer i && i >= 0 && i <= 30_000);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> BRAIN_DRAINS = BUILDER
            .comment("FE a whole tower uses every tick, for a lone brain and then for 1, 2, 3... floors. A taller tower uses the last entry")
            .defineList("drains", BrainBalance.DEFAULT_DRAINS, () -> 0, value -> value instanceof Integer i && i >= 0);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> BRAIN_POOLS = BUILDER
            .comment("FE a whole tower holds in one shared pool, for a lone brain and then for 1, 2, 3... floors. A taller tower uses the last entry")
            .defineList("pools", BrainBalance.DEFAULT_POOLS, () -> 100_000, value -> value instanceof Integer i && i >= 1);

    public static final ModConfigSpec.BooleanValue BRAIN_NO_LIMIT = BUILDER
            .comment("Every network holds any number of machines, with or without a Network Brain. Brains still use power")
            .define("noMachineLimit", false);

    public static final ModConfigSpec.DoubleValue BRAIN_MULTIPLIER = BUILDER
            .comment("Multiplies every machine limit above, the one without a brain too. 2 doubles them all")
            .defineInRange("machineMultiplier", 1.0, 0.1, 100.0);

    static {
        BUILDER.pop().push("wild_bitling");
    }

    public static final ModConfigSpec.IntValue WILD_SPAWN_RATE = BUILDER
            .comment("How often a growing Seeded Amethyst draws a wild Bitling over, in percent. 100 is normal, 0 means never")
            .defineInRange("spawnRate", 100, 0, 1_000);

    public static final ModConfigSpec.BooleanValue BASIC_BITLING_RECIPE = BUILDER
            .worldRestart()
            .comment("Lets players craft a Basic Bitling from Logic Chips, Memory Chips and an iron ingot, for worlds without wild Bitlings")
            .define("basicBitlingRecipe", false);


    public static final ModConfigSpec.IntValue WILD_FOLLOW_RANGE = BUILDER
            .comment("Blocks away a player can get before a wild Bitling stops following them")
            .defineInRange("followRange", 24, 4, 128);

    public static final ModConfigSpec.IntValue WILD_HEALTH = BUILDER
            .comment("Health of a wild Bitling (2 per heart)")
            .defineInRange("health", 10, 1, 100);

    static {
        BUILDER.pop().push("bays");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> BAY_CYCLE_TICKS = BUILDER
            .comment("Ticks one bay action takes with 0 to 4 Speed Upgrades. A four-entry list gives the fourth upgrade half the last entry; otherwise extra upgrades use the last. Under 20, two Bitlings take turns")
            .defineList("cycleTicks", List.of(40, 30, 20, 10, 5), () -> 20, value -> value instanceof Integer i && i >= 1);

    public static final ModConfigSpec.IntValue BAY_ENERGY_CAPACITY = BUILDER
            .comment("FE a bay holds")
            .defineInRange("energyCapacity", 10_000, 1, 1_000_000);

    public static final ModConfigSpec.IntValue BAY_TANK_BUCKETS = BUILDER
            .comment("Buckets each bay's tank holds")
            .defineInRange("tankBuckets", 16, 1, 1_000);

    public static final ModConfigSpec.IntValue DEMOLITION_FE_PER_POINT = BUILDER
            .comment("FE per point of the Demolition Bay's break cost (1 + hardness + drops, times enchantments)")
            .defineInRange("demolitionFePerPoint", 6, 0, 100_000);

    public static final ModConfigSpec.IntValue DEMOLITION_SCOOP_COST = BUILDER
            .comment("FE the Demolition Bay uses to scoop one bucket of fluid")
            .defineInRange("demolitionScoopCost", 50, 0, 1_000_000);

    public static final ModConfigSpec.IntValue DEPLOYMENT_COST = BUILDER
            .comment("FE the Deployment Bay uses per block placed, fluid placed or stack thrown")
            .defineInRange("deploymentCost", 20, 0, 1_000_000);

    public static final ModConfigSpec.IntValue BAY_DROP_ENTITY_LIMIT = BUILDER
            .comment("Drop mode pauses while this many entities are near the bay")
            .defineInRange("dropEntityLimit", 32, 1, 10_000);

    public static final ModConfigSpec.IntValue BAY_DROP_ENTITY_RADIUS = BUILDER
            .comment("How far, in blocks, drop mode looks for those entities")
            .defineInRange("dropEntityRadius", 8, 1, 64);

    static {
        BUILDER.pop().push("features");
    }

    public static final ModConfigSpec.BooleanValue FEATURE_DECK_TO_DECK = BUILDER
            .worldRestart()
            .comment("Deck to Deck sending. Off: nothing new can be sent, trips already on their way still arrive, and items waiting in the send grid or the inbox can still be taken out")
            .define("deckToDeck", true);

    public static final ModConfigSpec.BooleanValue FEATURE_BAYS = BUILDER
            .worldRestart()
            .comment("Deployment and Demolition Bays. Off: they can't be crafted, and placed ones stop working but keep their contents")
            .define("bays", true);

    public static final ModConfigSpec.BooleanValue FEATURE_GENERATORS = BUILDER
            .worldRestart()
            .comment("Combustion Generators. Off: they can't be crafted, and placed ones stop burning but keep their fuel and charge")
            .define("generators", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The value, or its default while no world has loaded the config yet (the guide draws blocks at the title screen). */
    public static int orDefault(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.getAsInt() : value.getDefault();
    }

    /** Wafer slots a Deck of this tier has in this world. */
    public static int deckSlots(DeckTier tier) {
        return orDefault(switch (tier) {
            case STARTER -> DECK_SLOTS_STARTER;
            case BASIC -> DECK_SLOTS_BASIC;
            case ADVANCED -> DECK_SLOTS_ADVANCED;
            case ELITE -> DECK_SLOTS_ELITE;
            case ULTIMATE -> DECK_SLOTS_ULTIMATE;
        });
    }

    private JasmConfig() {}
}
