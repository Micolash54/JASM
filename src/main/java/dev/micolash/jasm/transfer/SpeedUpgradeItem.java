package dev.micolash.jasm.transfer;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmItems;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class SpeedUpgradeItem extends Item {
    /** Most Speed Upgrades one machine takes, however many upgrade slots it has. */
    public static final int MAX = 4;

    public SpeedUpgradeItem(Properties properties) { super(properties); }

    /** Speed Upgrades in the slots {@code from} up to (not including) {@code to}, counting no more than {@link #MAX}. */
    public static int count(Container container, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) if (container.getItem(i).is(JasmItems.SPEED_UPGRADE.get())) count++;
        return Math.min(count, MAX);
    }

    /**
     * Whether {@code stack} may go in {@code slot} as far as the limit goes: a Speed Upgrade goes in an upgrade slot (those
     * from {@code from} up to {@code to}) only while the others hold fewer than {@link #MAX}. Anything else, or any other
     * slot, isn't limited here.
     */
    public static boolean fits(Container container, int from, int to, int slot, ItemStack stack) {
        if (slot < from || slot >= to || !stack.is(JasmItems.SPEED_UPGRADE.get())) return true;
        int others = 0;
        for (int i = from; i < to; i++) if (i != slot && container.getItem(i).is(JasmItems.SPEED_UPGRADE.get())) others++;
        return others < MAX;
    }

    /** How many times as fast a machine with {@code upgrades} Speed Upgrades works. Extra upgrades use the last entry. */
    public static double speed(int upgrades) {
        return entry(JasmConfig.SPEED_MULTIPLIERS.get(), upgrades);
    }

    /** What one job costs with {@code upgrades} Speed Upgrades, as a multiple of its base price. */
    public static double price(int upgrades) {
        return entry(JasmConfig.PRICE_MULTIPLIERS.get(), upgrades);
    }

    private static double entry(List<? extends Number> list, int upgrades) {
        return list.isEmpty() ? 1 : list.get(Math.clamp(upgrades, 0, list.size() - 1)).doubleValue();
    }

    /** The power for a job that costs {@code base} with no upgrades, in a machine holding {@code upgrades} Speed Upgrades. */
    public static long power(long base, int upgrades) {
        return Math.round(Math.max(0L, base) * price(upgrades));
    }

    /** How long a job of {@code base} ticks takes with {@code upgrades} Speed Upgrades: half ticks round up, at least one tick. */
    public static int ticks(int base, int upgrades) {
        return (int) Math.clamp(Math.round(base / speed(upgrades)), 1L, Integer.MAX_VALUE);
    }

    /**
     * Where a job that was {@code progress} ticks into {@code before} ticks stands when it becomes {@code after} ticks long:
     * the same share done, so adding or taking out a Speed Upgrade only changes how long the rest takes.
     */
    public static int keepShare(int progress, int before, int after) {
        if (progress <= 0 || before <= 0 || before == after) return progress;
        return Math.clamp((progress * (long) after + before / 2) / before, 0, Math.max(0, after - 1));
    }

    /** FE a tick for a job costing {@code total} spread over {@code ticks} ticks, rounded up. */
    public static int perTick(long total, int ticks) {
        long per = Math.max(1, ticks);
        return (int) Math.min(Integer.MAX_VALUE, (Math.max(0L, total) + per - 1) / per);
    }
}
