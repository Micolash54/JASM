package dev.micolash.jasm.transfer;

/** Item transfer timing and allowance shared by every port. */
public final class PortOperations {
    public static final int UPGRADE_SLOTS = 5;
    public static final int ACTIVE_INTERVAL = 10;
    public static final int SLEEP_INTERVAL = 20;
    public static final int IDLE_TICKS = 100;
    private static final int[] ITEMS = {1, 4, 16, 48, 96};

    /** What a port does with allowance it didn't use. */
    public enum Saving {
        /** Spent on a due operation or not at all. */
        NONE,
        /** Kept, up to {@link #savedCap}, and spent on any tick by everything the port does. */
        ALL,
        /** The port's own work spends it as with {@link #NONE}; what that leaves is kept, up to {@link #savedCap}, for pushes on any tick. */
        PUSHES
    }

    private long next = Long.MIN_VALUE;
    private long window = Long.MIN_VALUE;
    private long lastSuccess;
    private int credit;
    private int batch;
    private int used;
    /** With {@link Saving#PUSHES}: allowance the port's own work left over, kept for pushes. */
    private int saved;

    public static int itemsPerOperation(int upgrades) {
        return ITEMS[Math.clamp(upgrades, 0, SpeedUpgradeItem.MAX)];
    }

    /** The most allowance a port that saves it can hold: one second of busy operations. */
    public static int savedCap(int rate) {
        return rate * (20 / ACTIVE_INTERVAL);
    }

    /**
     * What the port's own work may spend now. Only a due operation can spend items, and unused allowance does not carry
     * over. With {@link Saving#ALL}, the allowance of each due operation is kept instead, up to {@link #savedCap}, and can be
     * spent on any tick. With {@link Saving#PUSHES} this is as with {@link Saving#NONE}, and what the cap would drop is kept
     * for {@link #forPush}.
     */
    public int available(long tick, int rate, Saving saving) {
        if (next == Long.MIN_VALUE) lastSuccess = tick;
        int cap = Math.max(saving == Saving.ALL ? savedCap(rate) : rate, batch);
        if (tick >= next) {
            if (saving == Saving.PUSHES) saved = Math.min(savedCap(rate) - rate, saved + Math.max(0, credit + rate - cap));
            window = tick;
            used = 0;
            credit = Math.min(cap, credit + rate);
            next = tick + (tick - lastSuccess >= IDLE_TICKS ? SLEEP_INTERVAL : ACTIVE_INTERVAL);
        }
        // fewer Speed Upgrades than when it was saved: never more than the cap allows now
        if (saving == Saving.ALL) return Math.max(0, Math.min(credit, cap));
        return window == tick ? Math.min(Math.max(0, rate - used), credit) : 0;
    }

    /**
     * What something pushing into the port may spend now, on any tick. With {@link Saving#PUSHES}: what the port's own work
     * left of its last due operation and what it saved, up to {@link #savedCap}. Otherwise the same as {@link #available}.
     */
    public int forPush(long tick, int rate, Saving saving) {
        int own = available(tick, rate, saving);
        if (saving != Saving.PUSHES) return own;
        saved = Math.min(saved, savedCap(rate) - rate);
        return Math.min(savedCap(rate), Math.max(0, credit) + saved);
    }

    /** Large recipe sets save allowance across operations, so all ingredients can enter together. */
    public boolean canSendBatch(long tick, int rate, int count, Saving saving) {
        batch = Math.max(batch, count);
        int free = available(tick, rate, saving);
        if (saving == Saving.ALL) return count > 0 && free >= count;
        return count > 0 && window == tick && credit >= count && (count > rate ? used == 0 : used + count <= rate);
    }

    /** The port's own work moved {@code count}. */
    public void transferred(long tick, int count, Saving saving) {
        if (count <= 0) return;
        credit -= count;
        used += count;
        if (count >= batch) batch = 0;
        lastSuccess = tick;
        // Busy again; a due operation that comes sooner stays.
        next = saving == Saving.ALL ? Math.min(next, tick + ACTIVE_INTERVAL) : tick + ACTIVE_INTERVAL;
    }

    /** A push moved {@code count}, within {@link #forPush}. */
    public void pushed(long tick, int count, Saving saving) {
        if (saving != Saving.PUSHES) {
            transferred(tick, count, saving);
            return;
        }
        if (count <= 0) return;
        int fromSaved = Math.min(saved, count);
        saved -= fromSaved;
        credit -= count - fromSaved;
        used += count - fromSaved;
        lastSuccess = tick;
        // the port's own operations keep their timing
        next = Math.min(next, tick + ACTIVE_INTERVAL);
    }
}
