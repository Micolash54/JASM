package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Jasm.MODID);

    public static final DeferredItem<BlockItem> CREATIVE_BATTERY = ITEMS.registerSimpleBlockItem(JasmBlocks.CREATIVE_BATTERY);
    public static final DeferredItem<BlockItem> COMBUSTION_GENERATOR = ITEMS.registerSimpleBlockItem(JasmBlocks.COMBUSTION_GENERATOR);

    private static final Map<ArchiveTier, DeferredItem<BlockItem>> ARCHIVES = new EnumMap<>(ArchiveTier.class);
    private static final Map<WaferTier, DeferredItem<WaferItem>> WAFERS = new EnumMap<>(WaferTier.class);
    private static final Map<DeckTier, DeferredItem<DeckItem>> DECKS = new EnumMap<>(DeckTier.class);

    static {
        for (DeckTier tier : DeckTier.values()) {
            DECKS.put(tier, ITEMS.registerItem(tier.registryName(), p -> new DeckItem(p, tier)));
        }
        for (ArchiveTier tier : ArchiveTier.values()) {
            ARCHIVES.put(tier, ITEMS.registerSimpleBlockItem(JasmBlocks.archive(tier), p -> p.stacksTo(1)));
        }
        for (WaferTier tier : WaferTier.values()) {
            WAFERS.put(tier, ITEMS.registerItem(tier.registryName(), p -> new WaferItem(p, tier)));
        }
    }

    public static WaferItem wafer(WaferTier tier) {
        return WAFERS.get(tier).get();
    }

    public static BlockItem archive(ArchiveTier tier) {
        return ARCHIVES.get(tier).get();
    }

    public static DeckItem deck(DeckTier tier) {
        return DECKS.get(tier).get();
    }

    private JasmItems() {}
}
