package dev.micolash.jasm.core;

/** How a Network Brain grows: its level bars, the cap its size allows, and how many machines each level lets in. */
public final class BrainLevels {
    private BrainLevels() {}

    /** The highest level a brain of this size reaches. */
    public static int cap(BrainSize size, BrainBalance b) {
        return Math.clamp(b.levelCaps().get(size.ordinal()), 1, BrainBalance.MAX_LEVEL);
    }

    /** The level that counts: the real one, held down to the size's cap. */
    public static int shownLevel(BrainProgress p, BrainSize size, BrainBalance b) {
        return Math.min(p.level(), cap(size, b));
    }

    /** At or past the size's top level: it learns nothing more until it grows. */
    public static boolean capped(BrainProgress p, BrainSize size, BrainBalance b) {
        return p.level() >= cap(size, b);
    }

    /** Machines a network may hold at {@code level}; 0 means no working brain. */
    public static int machineLimit(int level, BrainBalance b) {
        if (level <= 0) {
            return b.limitWithoutBrain();
        }
        return b.machineLimits().get(Math.min(level, BrainBalance.MAX_LEVEL) - 1);
    }

    /** Points from {@code level} to the next; 0 at the top. */
    public static long bar(int level, BrainBalance b) {
        return level < 1 || level >= BrainBalance.MAX_LEVEL ? 0 : b.levelPoints().get(level - 1);
    }

    /** The progress after learning {@code points} more. Stops at the size's cap; locked progress above it stays as it is. */
    public static BrainProgress add(BrainProgress p, long points, BrainSize size, BrainBalance b) {
        int cap = cap(size, b);
        int level = p.level();
        long have = p.points();
        long left = points;
        while (left > 0 && level < cap) {
            // Saved points can sit above a bar the config has since lowered.
            long need = Math.max(0, bar(level, b) - have);
            if (left < need) {
                have += left;
                left = 0;
            } else {
                level++;
                have = 0;
                left -= need;
            }
        }
        return level == p.level() && have == p.points() ? p : new BrainProgress(level, have);
    }

    /** Points learned each powered tick. */
    public static long passive(BrainSize size, BrainBalance b) {
        return b.learnSpeed().get(size.ordinal());
    }

    /** Points one chip gives: a flat part of the current level's bar. */
    public static long chipPoints(BrainProgress p, boolean advanced, BrainBalance b) {
        return bar(p.level(), b) * (advanced ? b.advancedChipBonus() : b.chipBonus()) / 100;
    }

    /** How full the shown level's bar is, 0 to 100. Locked or top-level brains show 100. */
    public static int percent(BrainProgress p, BrainSize size, BrainBalance b) {
        long bar = bar(p.level(), b);
        if (p.level() > cap(size, b) || bar == 0) {
            return 100;
        }
        // Saved points can sit above a bar the config has since lowered.
        return (int) Math.min(100, p.points() * 100 / bar);
    }

    /** FE a brain of this size uses each tick. */
    public static int drain(BrainSize size, BrainBalance b) {
        return b.drainPerTick().get(size.ordinal());
    }
}
