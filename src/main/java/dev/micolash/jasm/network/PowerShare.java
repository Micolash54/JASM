package dev.micolash.jasm.network;

import java.util.List;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Splits power between several blocks that want it. */
public final class PowerShare {
    private PowerShare() {}

    /** Rounds of equal parts before the rest goes to whoever can take it. */
    private static final int ROUNDS = 4;

    /**
     * An equal part to each that still has room. What one couldn't take is shared out equally again between those that
     * still have room, and after a few rounds the rest goes to whoever can take it. {@code start} picks who goes first,
     * so the odd FE left over doesn't always land on the same block. Returns what was taken.
     */
    public static int spread(List<? extends EnergyHandler> targets, int amount, long start, TransactionContext transaction) {
        if (amount <= 0 || targets.isEmpty()) {
            return 0;
        }
        int first = (int) Math.floorMod(start, (long) targets.size());
        int taken = 0;
        for (int round = 0; round < ROUNDS && taken < amount; round++) {
            int wanting = 0;
            for (EnergyHandler target : targets) {
                if (target.getAmountAsLong() < target.getCapacityAsLong()) wanting++;
            }
            if (wanting == 0) {
                // Nobody asked for any: nothing is pushed. Later rounds still offer the rest below.
                if (round == 0) return 0;
                break;
            }
            int share = Math.max(1, (amount - taken) / wanting);
            int before = taken;
            for (int i = 0; i < targets.size() && taken < amount; i++) {
                taken += targets.get((first + i) % targets.size()).insert(Math.min(share, amount - taken), transaction);
            }
            if (taken == before) {
                break;
            }
        }
        for (int i = 0; i < targets.size() && taken < amount; i++) {
            taken += targets.get((first + i) % targets.size()).insert(amount - taken, transaction);
        }
        return taken;
    }
}
