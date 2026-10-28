package dev.micolash.jasm.transfer;

/** Item transfer timing and allowance shared by every port. */
public final class PortOperations {
    public static final int UPGRADE_SLOTS = 5;
    public static final int ACTIVE_INTERVAL = 10;
    public static final int SLEEP_INTERVAL = 20;
    public static final int IDLE_TICKS = 100;
    private static final int[] ITEMS = {1, 4, 16, 48, 96};
    private long next = Long.MIN_VALUE;
    private long window = Long.MIN_VALUE;
    private long lastSuccess;
    private int credit;
    private int batch;
    private int used;

    public static int itemsPerOperation(int upgrades) {
        return ITEMS[Math.clamp(upgrades, 0, SpeedUpgradeItem.MAX)];
    }

    /** The most allowance a port that saves it can hold: one second of busy operations. */
    public static int savedCap(int rate) {
        return rate * (20 / ACTIVE_INTERVAL);
    }

    /**
     * Only a due operation can spend items, and unused allowance does not carry over. With {@code saves}, the allowance
     * of each due operation is kept instead, up to {@link #savedCap}, and can be spent on any tick.
     */
    public int available(long tick, int rate, boolean saves) {
        if (next == Long.MIN_VALUE) lastSuccess = tick;
        int cap = Math.max(saves ? savedCap(rate) : rate, batch);
        if (tick >= next) {
            window = tick;
            used = 0;
            credit = Math.min(cap, credit + rate);
            next = tick + (tick - lastSuccess >= IDLE_TICKS ? SLEEP_INTERVAL : ACTIVE_INTERVAL);
        }
        // fewer Speed Upgrades than when it was saved: never more than the cap allows now
        if (saves) return Math.max(0, Math.min(credit, cap));
        return window == tick ? Math.min(Math.max(0, rate - used), credit) : 0;
    }

    /** Large recipe sets save allowance across operations, so all ingredients can enter together. */
    public boolean canSendBatch(long tick, int rate, int count, boolean saves) {
        batch = Math.max(batch, count);
        int free = available(tick, rate, saves);
        if (saves) return count > 0 && free >= count;
        return count > 0 && window == tick && credit >= count && (count > rate ? used == 0 : used + count <= rate);
    }

    public void transferred(long tick, int count, boolean saves) {
        if (count <= 0) return;
        credit -= count;
        used += count;
        if (count >= batch) batch = 0;
        lastSuccess = tick;
        // Busy again; a due operation that comes sooner stays.
        next = saves ? Math.min(next, tick + ACTIVE_INTERVAL) : tick + ACTIVE_INTERVAL;
    }
}
