package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.autocraft.RecipeCardItem;
import dev.micolash.jasm.autocraft.ServerPartItem;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
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
