package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.autocraft.RecipeCardItem;
import dev.micolash.jasm.autocraft.ServerPartItem;
import dev.micolash.jasm.autocraft.ThinAccessPortItem;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.core.ChipType;
import dev.micolash.jasm.crystal.CrystalSeedItem;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Jasm.MODID);

    public static final DeferredItem<BlockItem> CREATIVE_BATTERY = ITEMS.registerSimpleBlockItem(JasmBlocks.CREATIVE_BATTERY);
    public static final DeferredItem<Item> RECIPE_CARD = ITEMS.registerSimpleItem("recipe_card");
    public static final DeferredItem<RecipeCardItem> FILLED_RECIPE_CARD = ITEMS.registerItem("filled_recipe_card", RecipeCardItem::new,
            p -> p.stacksTo(1));
    public static final DeferredItem<BlockItem> ENCODING_TERMINAL = ITEMS.registerSimpleBlockItem(JasmBlocks.ENCODING_TERMINAL);
    public static final DeferredItem<BlockItem> RECIPE_RACK = ITEMS.registerSimpleBlockItem(JasmBlocks.RECIPE_RACK);
    private static final List<DeferredItem<BlockItem>> CABLES = new ArrayList<>();
    public static final DeferredItem<BlockItem> CRAFTING_SERVER = ITEMS.registerSimpleBlockItem(JasmBlocks.CRAFTING_SERVER);
    public static final DeferredItem<BlockItem> ACCESS_PORT = ITEMS.registerSimpleBlockItem(JasmBlocks.ACCESS_PORT);
    public static final DeferredItem<ThinAccessPortItem> THIN_ACCESS_PORT = ITEMS.registerItem("thin_access_port", ThinAccessPortItem::new);
    public static final DeferredItem<Item> POWER_UPGRADE = ITEMS.registerSimpleItem("power_upgrade");
    public static final DeferredItem<CrystalSeedItem> CRYSTAL_SEED = ITEMS.registerItem("crystal_seed", CrystalSeedItem::new);
    public static final DeferredItem<Item> DATA_CRYSTAL = ITEMS.registerSimpleItem("data_crystal");
    public static final DeferredItem<Item> CRYSTAL_DUST = ITEMS.registerSimpleItem("crystal_dust");
    public static final DeferredItem<BlockItem> SEEDED_AMETHYST = ITEMS.registerSimpleBlockItem(JasmBlocks.SEEDED_AMETHYST);
    public static final DeferredItem<BlockItem> WORN_SEEDED_AMETHYST = ITEMS.registerSimpleBlockItem(JasmBlocks.WORN_SEEDED_AMETHYST);
    public static final DeferredItem<BlockItem> CRACKED_SEEDED_AMETHYST = ITEMS.registerSimpleBlockItem(JasmBlocks.CRACKED_SEEDED_AMETHYST);
    public static final DeferredItem<BlockItem> SMALL_DATA_CRYSTAL_BUD = ITEMS.registerSimpleBlockItem(JasmBlocks.SMALL_DATA_CRYSTAL_BUD);
    public static final DeferredItem<BlockItem> MEDIUM_DATA_CRYSTAL_BUD = ITEMS.registerSimpleBlockItem(JasmBlocks.MEDIUM_DATA_CRYSTAL_BUD);
    public static final DeferredItem<BlockItem> LARGE_DATA_CRYSTAL_BUD = ITEMS.registerSimpleBlockItem(JasmBlocks.LARGE_DATA_CRYSTAL_BUD);
    public static final DeferredItem<BlockItem> DATA_CRYSTAL_CLUSTER = ITEMS.registerSimpleBlockItem(JasmBlocks.DATA_CRYSTAL_CLUSTER);
    public static final DeferredItem<Item> BLANK_CHIP = ITEMS.registerSimpleItem("blank_chip");
    public static final DeferredItem<Item> UNQUENCHED_LOGIC_CHIP = ITEMS.registerSimpleItem("unquenched_logic_chip");
    public static final DeferredItem<Item> UNQUENCHED_MEMORY_CHIP = ITEMS.registerSimpleItem("unquenched_memory_chip");
    public static final DeferredItem<Item> LOGIC_CHIP = ITEMS.registerSimpleItem("logic_chip");
    public static final DeferredItem<Item> MEMORY_CHIP = ITEMS.registerSimpleItem("memory_chip");
    public static final DeferredItem<Item> LINK_CHIP = ITEMS.registerSimpleItem("link_chip");
    public static final DeferredItem<Item> ADVANCED_LOGIC_CHIP = ITEMS.registerSimpleItem("advanced_logic_chip");
    public static final DeferredItem<Item> ADVANCED_MEMORY_CHIP = ITEMS.registerSimpleItem("advanced_memory_chip");
    public static final DeferredItem<Item> ADVANCED_LINK_CHIP = ITEMS.registerSimpleItem("advanced_link_chip");
    private static final Map<BitlingKind, Map<BitlingStage, DeferredItem<BitlingItem>>> BITLINGS = new EnumMap<>(BitlingKind.class);

    static {
        for (BitlingKind kind : BitlingKind.values()) {
            Map<BitlingStage, DeferredItem<BitlingItem>> stages = new EnumMap<>(BitlingStage.class);
            for (BitlingStage stage : BitlingStage.values()) {
                // A Basic Bitling never grows up.
                if (kind != BitlingKind.BASIC || stage == BitlingStage.BITLING) {
                    stages.put(stage, ITEMS.registerItem(BitlingItem.registryName(kind, stage), p -> new BitlingItem(p, kind, stage)));
                }
            }
            BITLINGS.put(kind, stages);
        }
    }

    public static final DeferredItem<BlockItem> CRYSTAL_RESONATOR = ITEMS.registerSimpleBlockItem(JasmBlocks.CRYSTAL_RESONATOR);
    public static final DeferredItem<BlockItem> CHIP_WORKSHOP = ITEMS.registerSimpleBlockItem(JasmBlocks.CHIP_WORKSHOP);
    public static final DeferredItem<BlockItem> CRYSTAL_FOUNDRY = ITEMS.registerSimpleBlockItem(JasmBlocks.CRYSTAL_FOUNDRY);
    public static final DeferredItem<BlockItem> BITLING_STATION = ITEMS.registerSimpleBlockItem(JasmBlocks.BITLING_STATION);
    private static final Map<ProcessorTier, DeferredItem<ServerPartItem>> PROCESSORS = new EnumMap<>(ProcessorTier.class);
    private static final Map<MemoryTier, DeferredItem<ServerPartItem>> MODULES = new EnumMap<>(MemoryTier.class);

    private static final Map<ArchiveTier, DeferredItem<BlockItem>> ARCHIVES = new EnumMap<>(ArchiveTier.class);
    private static final Map<WaferTier, DeferredItem<WaferItem>> WAFERS = new EnumMap<>(WaferTier.class);
    private static final Map<DeckTier, DeferredItem<DeckItem>> DECKS = new EnumMap<>(DeckTier.class);
    private static final Map<DeckTier, DeferredItem<DeckItem>> CRAFTING_DECKS = new EnumMap<>(DeckTier.class);
    private static final Map<GeneratorTier, DeferredItem<BlockItem>> GENERATORS = new EnumMap<>(GeneratorTier.class);

    static {
        for (DeckTier tier : DeckTier.values()) {
            DECKS.put(tier, ITEMS.registerItem(tier.registryName(), p -> new DeckItem(p, tier, false)));
        }
        for (DeckTier tier : DeckTier.values()) {
            if (tier.hasCraftingDeck()) {
                CRAFTING_DECKS.put(tier, ITEMS.registerItem(tier.craftingRegistryName(), p -> new DeckItem(p, tier, true)));
            }
        }
        for (ArchiveTier tier : ArchiveTier.values()) {
            ARCHIVES.put(tier, ITEMS.registerSimpleBlockItem(JasmBlocks.archive(tier), p -> p.stacksTo(1)));
        }
        for (WaferTier tier : WaferTier.values()) {
            WAFERS.put(tier, ITEMS.registerItem(tier.registryName(), p -> new WaferItem(p, tier)));
        }
        for (GeneratorTier tier : GeneratorTier.values()) {
            GENERATORS.put(tier, ITEMS.registerSimpleBlockItem(JasmBlocks.generator(tier)));
        }
        JasmBlocks.cables().forEach(cable -> CABLES.add(ITEMS.registerSimpleBlockItem(cable)));
        for (ProcessorTier tier : ProcessorTier.values()) {
            PROCESSORS.put(tier, ITEMS.registerItem(tier.registryName(), p -> ServerPartItem.processor(p, tier), p -> p.stacksTo(16)));
        }
        for (MemoryTier tier : MemoryTier.values()) {
            MODULES.put(tier, ITEMS.registerItem(tier.registryName(), p -> ServerPartItem.memory(p, tier), p -> p.stacksTo(16)));
        }
    }

    public static WaferItem wafer(WaferTier tier) {
        return WAFERS.get(tier).get();
    }

    public static BlockItem archive(ArchiveTier tier) {
        return ARCHIVES.get(tier).get();
    }

    public static BlockItem generator(GeneratorTier tier) {
        return GENERATORS.get(tier).get();
    }

    public static DeckItem deck(DeckTier tier) {
        return DECKS.get(tier).get();
    }

    /** The Crafting Deck of {@code tier}; only for tiers where {@link DeckTier#hasCraftingDeck()}. */
    public static DeckItem craftingDeck(DeckTier tier) {
        return CRAFTING_DECKS.get(tier).get();
    }

    public static ServerPartItem processor(ProcessorTier tier) {
        return PROCESSORS.get(tier).get();
    }

    public static ServerPartItem module(MemoryTier tier) {
        return MODULES.get(tier).get();
    }

    /** The critter of {@code kind} at {@code stage}; a Basic Bitling only exists at {@link BitlingStage#BITLING}. */
    public static BitlingItem bitling(BitlingKind kind, BitlingStage stage) {
        return BITLINGS.get(kind).get(stage).get();
    }

    /** Every critter, Basic first, then each type from young to grown. */
    public static List<BitlingItem> bitlings() {
        List<BitlingItem> all = new ArrayList<>();
        BITLINGS.values().forEach(stages -> stages.values().forEach(b -> all.add(b.get())));
        return all;
    }

    public static Item chip(ChipType type, boolean advanced) {
        return (switch (type) {
            case LOGIC -> advanced ? ADVANCED_LOGIC_CHIP : LOGIC_CHIP;
            case MEMORY -> advanced ? ADVANCED_MEMORY_CHIP : MEMORY_CHIP;
            case LINK -> advanced ? ADVANCED_LINK_CHIP : LINK_CHIP;
        }).get();
    }

    /** Every Data Cable item, undyed first. */
    public static List<BlockItem> cables() {
        return CABLES.stream().map(DeferredItem::get).toList();
    }

    /** Every Deck, normal and crafting. */
    public static List<DeckItem> allDecks() {
        List<DeckItem> decks = new ArrayList<>();
        DECKS.values().forEach(d -> decks.add(d.get()));
        CRAFTING_DECKS.values().forEach(d -> decks.add(d.get()));
        return decks;
    }

    private JasmItems() {}
}
