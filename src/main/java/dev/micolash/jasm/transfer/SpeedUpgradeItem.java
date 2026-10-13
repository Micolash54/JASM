package dev.micolash.jasm.transfer;

import dev.micolash.jasm.bay.BayClock;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmItems;
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

    /**
     * The power for a job that costs {@code base} on its own, in a machine holding {@code upgrades} Speed Upgrades. A job costs
     * as many times more as the upgrades make the machine faster: twice as fast, twice the power for the same job.
     */
    public static int power(int base, int upgrades) {
        var table = JasmConfig.BAY_CYCLE_TICKS.get();
        long slow = BayClock.ticksFor(0, table);
        long fast = BayClock.ticksFor(upgrades, table);
        return (int) Math.min(Integer.MAX_VALUE, (Math.max(0L, base) * slow + fast - 1) / fast);
    }

    /** How long a job of {@code base} ticks takes with {@code upgrades} Speed Upgrades: the bays' speed-up applied to it. */
    public static int ticks(int base, int upgrades) {
        var table = JasmConfig.BAY_CYCLE_TICKS.get();
        long slow = BayClock.ticksFor(0, table);
        long fast = BayClock.ticksFor(upgrades, table);
        return (int) Math.max(1, (base * fast + slow / 2) / slow);
    }

    /**
     * The power a machine uses each tick for a job of {@code baseTicks} ticks, normally drawing {@code baseDrain}, once it takes
     * {@code ticks} ticks: the same job, paid for with the extra power, in less time.
     */
    public static int perTick(int baseDrain, int baseTicks, int ticks, int upgrades) {
        long whole = (long) power(baseDrain, upgrades) * baseTicks;
        long per = Math.max(1, ticks);
        return (int) Math.min(Integer.MAX_VALUE, (whole + per - 1) / per);
    }
}
