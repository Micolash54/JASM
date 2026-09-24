package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.EnumMap;
import java.util.Map;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Jasm.MODID);

    private static final Map<WaferTier, DeferredItem<WaferItem>> WAFERS = new EnumMap<>(WaferTier.class);

    static {
        for (WaferTier tier : WaferTier.values()) {
            WAFERS.put(tier, ITEMS.registerItem(tier.registryName(), p -> new WaferItem(p, tier)));
        }
    }

    public static WaferItem wafer(WaferTier tier) {
        return WAFERS.get(tier).get();
    }

    private JasmItems() {}
}
