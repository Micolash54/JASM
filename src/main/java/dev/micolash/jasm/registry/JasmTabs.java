package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Jasm.MODID);

    /** Build 1 is creative-only: every JASM item is obtained here. */
    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.jasm.main"))
            .icon(() -> JasmItems.wafer(WaferTier.K1).getDefaultInstance())
            .displayItems((parameters, output) -> JasmItems.ITEMS.getEntries().forEach(item -> output.accept(item.get())))
            .build());

    private JasmTabs() {}
}
