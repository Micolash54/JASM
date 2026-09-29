package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Jasm.MODID);

    /** Every JASM item. Each Deck is listed empty and fully charged. */
    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.jasm.main"))
            .icon(() -> JasmItems.deck(DeckTier.ULTIMATE).getDefaultInstance())
            .displayItems((parameters, output) -> {
                for (DeckItem deck : JasmItems.allDecks()) {
                    output.accept(deck);
                    ItemStack charged = new ItemStack(deck);
                    charged.set(JasmComponents.ENERGY.get(), deck.tier().battery());
                    output.accept(charged);
                }
                for (WaferTier tier : WaferTier.values()) {
                    output.accept(JasmItems.wafer(tier));
                }
                for (ArchiveTier tier : ArchiveTier.values()) {
                    output.accept(JasmItems.archive(tier));
                }
                for (GeneratorTier tier : GeneratorTier.values()) {
                    output.accept(JasmItems.generator(tier));
                }
                output.accept(JasmItems.ENCODING_TERMINAL);
                output.accept(JasmItems.RECIPE_RACK);
                output.accept(JasmItems.CRAFTING_SERVER);
                output.accept(JasmItems.ACCESS_PORT);
                for (ProcessorTier tier : ProcessorTier.values()) {
                    output.accept(JasmItems.processor(tier));
                }
                for (MemoryTier tier : MemoryTier.values()) {
                    output.accept(JasmItems.module(tier));
                }
                output.accept(JasmItems.RECIPE_CARD);
                output.accept(JasmItems.CRYSTAL_SEED);
                output.accept(JasmItems.SEEDED_AMETHYST);
                output.accept(JasmItems.WORN_SEEDED_AMETHYST);
                output.accept(JasmItems.CRACKED_SEEDED_AMETHYST);
                output.accept(JasmItems.SMALL_DATA_CRYSTAL_BUD);
                output.accept(JasmItems.MEDIUM_DATA_CRYSTAL_BUD);
                output.accept(JasmItems.LARGE_DATA_CRYSTAL_BUD);
                output.accept(JasmItems.DATA_CRYSTAL_CLUSTER);
                output.accept(JasmItems.CRYSTAL_DUST);
                output.accept(JasmItems.DATA_CRYSTAL);
                output.accept(JasmItems.CRYSTAL_RESONATOR);
                output.accept(JasmItems.CHIP_WORKSHOP);
                output.accept(JasmItems.CRYSTAL_FOUNDRY);
                output.accept(JasmItems.BLANK_CHIP);
                output.accept(JasmItems.UNQUENCHED_LOGIC_CHIP);
                output.accept(JasmItems.UNQUENCHED_MEMORY_CHIP);
                output.accept(JasmItems.LOGIC_CHIP);
                output.accept(JasmItems.MEMORY_CHIP);
                output.accept(JasmItems.LINK_CHIP);
                output.accept(JasmItems.ADVANCED_LOGIC_CHIP);
                output.accept(JasmItems.ADVANCED_MEMORY_CHIP);
                output.accept(JasmItems.ADVANCED_LINK_CHIP);
                for (BitlingItem bitling : JasmItems.bitlings()) {
                    output.accept(bitling);
                    ItemStack charged = new ItemStack(bitling);
                    charged.set(JasmComponents.ENERGY.get(), bitling.battery());
                    output.accept(charged);
                }
                JasmItems.cables().forEach(output::accept);
                output.accept(JasmItems.CREATIVE_BATTERY);
            })
            .build());

    private JasmTabs() {}
}
