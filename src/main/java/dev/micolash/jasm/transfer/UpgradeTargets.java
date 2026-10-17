package dev.micolash.jasm.transfer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.bay.BayBlockEntity;
import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;

/**
 * Where each upgrade can go. Each machine is asked through its own slot rules, on an empty machine, so the list follows
 * the machines. Thin and block ports share their names and their rules, so each is listed once.
 */
public final class UpgradeTargets {
    private UpgradeTargets() {}

    /** One machine: the name it shows under and whether its upgrade slots take an item. */
    private record Place(Component name, Predicate<ItemStack> takes) {}

    /** The machines {@code stack} can be installed in, in tooltip order. Empty for anything no upgrade slot takes. */
    public static List<Component> worksIn(ItemStack stack) {
        return Cache.BY_ITEM.getOrDefault(stack.getItem(), List.of());
    }

    /** Built once, on first use, for every JASM item: the registries are fixed by then and the answer never changes. */
    private static final class Cache {
        static final Map<Item, List<Component>> BY_ITEM = build();
    }

    private static Map<Item, List<Component>> build() {
        int[] portSlots = IntStream.concat(IntStream.of(AccessPortBlockEntity.POWER_SLOT),
                IntStream.range(AccessPortBlockEntity.SPEED_START, AccessPortBlockEntity.INVENTORY_SIZE)).toArray();
        int[] baySlots = IntStream.range(BayBlockEntity.UPGRADE_START, BayBlockEntity.SLOTS).toArray();
        List<Place> places = List.of(
                machine(JasmBlocks.FULL_INPUT_PORT.get(), portSlots),
                machine(JasmBlocks.FULL_OUTPUT_PORT.get(), portSlots),
                machine(JasmBlocks.FULL_INPUT_OUTPUT_PORT.get(), portSlots),
                machine(JasmBlocks.ACCESS_PORT.get(), portSlots),
                machine(JasmBlocks.DEPLOYMENT_BAY.get(), baySlots),
                machine(JasmBlocks.DEMOLITION_BAY.get(), baySlots),
                machine(JasmBlocks.CRYSTAL_FOUNDRY.get(),
                        IntStream.range(CrystalFoundryBlockEntity.UPGRADE_START, CrystalFoundryBlockEntity.SLOTS).toArray()),
                machine(JasmBlocks.CHIP_WORKSHOP.get(),
                        IntStream.range(ChipWorkshopBlockEntity.UPGRADE_START, ChipWorkshopBlockEntity.SLOTS).toArray()),
                new Place(Component.translatable("tooltip.jasm.upgrade.decks"), DeckItem::takesUpgrade));
        Map<Item, List<Component>> out = new IdentityHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(Jasm.MODID)) continue;
            ItemStack stack = new ItemStack(item);
            List<Component> names = places.stream().filter(place -> place.takes().test(stack)).map(Place::name).toList();
            if (!names.isEmpty()) out.put(item, names);
        }
        return Map.copyOf(out);
    }

    /** A machine block, asked through a fresh, empty copy of its own block entity. */
    private static Place machine(Block block, int[] upgradeSlots) {
        Container machine = (Container) ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
        return new Place(block.getName(), stack -> {
            for (int slot : upgradeSlots) if (machine.canPlaceItem(slot, stack)) return true;
            return false;
        });
    }
}
