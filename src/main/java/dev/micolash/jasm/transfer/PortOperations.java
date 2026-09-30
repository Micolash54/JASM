package dev.micolash.jasm.transfer;

/** Item transfer timing and allowance shared by every port. */
public final class PortOperations {
    public static final int UPGRADE_SLOTS = 4;
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
        return ITEMS[Math.clamp(upgrades, 0, UPGRADE_SLOTS)];
    }

    /** Only a due operation can spend items. Unused allowance normally does not carry over. */
    public int available(long tick, int rate) {
        if (next == Long.MIN_VALUE) lastSuccess = tick;
        if (tick >= next) {
            window = tick;
            used = 0;
            credit = Math.min(Math.max(rate, batch), credit + rate);
            next = tick + (tick - lastSuccess >= IDLE_TICKS ? SLEEP_INTERVAL : ACTIVE_INTERVAL);
        }
        return window == tick ? Math.min(Math.max(0, rate - used), credit) : 0;
    }

    /** Large recipe sets save allowance across operations, so all ingredients can enter together. */
    public boolean canSendBatch(long tick, int rate, int count) {
        batch = Math.max(batch, count);
        available(tick, rate);
        return count > 0 && window == tick && credit >= count && (count > rate ? used == 0 : used + count <= rate);
    }

    public void transferred(long tick, int count) {
        if (count <= 0) return;
        credit -= count;
        used += count;
        if (count >= batch) batch = 0;
        lastSuccess = tick;
        next = tick + ACTIVE_INTERVAL;
    }
}
