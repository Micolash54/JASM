package dev.micolash.jasm.gametest;

import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferEligibility;
import dev.micolash.jasm.wafer.WaferEligibility.Result;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/** What wafers refuse to store. */
final class EligibilityGameTests {
    private EligibilityGameTests() {}

    static void register() {
        JasmGameTests.add("eligibility_rules", EligibilityGameTests::rules);
    }

    private static Result check(GameTestHelper helper, ItemStack stack) {
        return WaferEligibility.check(stack, helper.getLevel().registryAccess());
    }

    private static void rules(GameTestHelper helper) {
        helper.assertTrue(check(helper, new ItemStack(Items.DIAMOND, 64)) == Result.OK, "plain diamonds are stored");
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        helper.assertTrue(check(helper, named) == Result.OK, "named tools are stored");
        helper.assertTrue(check(helper, ItemStack.EMPTY) == Result.EMPTY, "nothing is not stored");

        ItemStack filledChest = new ItemStack(Items.CHEST);
        filledChest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        ItemStack lootChest = new ItemStack(Items.CHEST);
        lootChest.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(BuiltInLootTables.SIMPLE_DUNGEON, 1L));
        ItemStack fullBundle = new ItemStack(Items.BUNDLE);
        fullBundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.DIAMOND))));
        List<ItemStack> containers = List.of(
                new ItemStack(JasmItems.wafer(WaferTier.BASIC)),
                new ItemStack(JasmItems.deck(DeckTier.STARTER)),
                new ItemStack(Items.SHULKER_BOX),
                fullBundle,
                filledChest,
                lootChest);
        for (ItemStack container : containers) {
            helper.assertTrue(check(helper, container) == Result.CONTAINER, "refused as a container: " + container);
        }
        helper.assertTrue(check(helper, new ItemStack(Items.CHEST)) == Result.OK, "an empty chest item is just a block");
        helper.assertTrue(check(helper, new ItemStack(Items.BUNDLE)) == Result.OK, "an empty bundle holds nothing");

        ItemStack huge = new ItemStack(Items.PAPER);
        huge.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(40_000)));
        helper.assertTrue(check(helper, huge) == Result.TOO_LARGE, "oversized item data is refused");
        helper.succeed();
    }
}
