package dev.micolash.jasm.network;

/**
 * FE a tick something handed on, averaged over the last full second. A second nobody looked at reads as nothing handed
 * on since. Owned by the block that counts; nothing else keeps it.
 */
public final class FlowMeter {
    private static final int TICKS = 20;

    private long windowStart = Long.MIN_VALUE;
    private long moved;
    private int flow;

    public void add(long now, int amount) {
        roll(now);
        moved += amount;
    }

    public int flow(long now) {
        roll(now);
        return flow;
    }

    private void roll(long now) {
        if (windowStart == Long.MIN_VALUE || now < windowStart) {
            windowStart = now;
            moved = 0;
            flow = 0;
        } else if (now - windowStart >= TICKS) {
            flow = now - windowStart < 2L * TICKS ? (int) Math.min(Integer.MAX_VALUE, Math.round(moved / (double) TICKS)) : 0;
            windowStart = now;
            moved = 0;
        }
    }
}
