package dev.micolash.jasm.gametest;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferTier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

/** Proves the mod loads and registers its items on a real server. */
final class SmokeGameTests {
    private SmokeGameTests() {}

    static void register() {
        JasmGameTests.add("smoke_wafers_registered_and_blank", SmokeGameTests::wafersRegisteredAndBlank);
    }

    private static void wafersRegisteredAndBlank(GameTestHelper helper) {
        for (WaferTier tier : WaferTier.values()) {
            ItemStack wafer = new ItemStack(JasmItems.wafer(tier));
            helper.assertTrue(wafer.getMaxStackSize() == 1, tier + " wafers do not stack");
            helper.assertFalse(wafer.has(JasmComponents.WAFER_IDENTITY.get()), tier + " starts blank");
        }
        helper.succeed();
    }
}
