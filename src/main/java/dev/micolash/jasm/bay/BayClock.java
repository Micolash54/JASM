package dev.micolash.jasm.bay;

import java.util.List;

/** One bay action is one cycle. Counts the cycle down and keeps at most one redstone pulse waiting. */
public final class BayClock {
    private int length;
    private int left;
    private boolean pulse;

    /** Four-entry settings give the fourth upgrade half the last time; other missing entries use the last. */
    public static int ticksFor(int speedUpgrades, List<? extends Integer> table) {
        if (table.isEmpty()) return 20;
        if (speedUpgrades >= 4 && table.size() == 4) return Math.max(1, table.getLast() / 2);
        int index = Math.clamp(speedUpgrades, 0, table.size() - 1);
        return Math.max(1, table.get(index));
    }

    public void start(int ticks) {
        length = Math.max(1, ticks);
        left = length;
    }

    /** One tick passes. True on the tick the cycle ends. */
    public boolean advance() {
        if (left <= 0) return false;
        return --left == 0;
    }

    public void cancel() {
        left = 0;
    }

    public boolean running() {
        return left > 0;
    }

    public int length() {
        return length;
    }

    public int elapsed() {
        return length - left;
    }

    /** A rising redstone edge. Several before one is used count once. */
    public void pulse() {
        pulse = true;
    }

    /** Uses the waiting pulse, if any. */
    public boolean takePulse() {
        boolean had = pulse;
        pulse = false;
        return had;
    }
}
