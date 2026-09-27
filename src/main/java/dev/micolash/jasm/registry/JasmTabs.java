package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.wafer.WaferTier;
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
                JasmItems.cables().forEach(output::accept);
                output.accept(JasmItems.CREATIVE_BATTERY);
            })
            .build());

    private JasmTabs() {}
}
