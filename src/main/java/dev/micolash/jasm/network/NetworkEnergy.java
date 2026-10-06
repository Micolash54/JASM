package dev.micolash.jasm.network;

import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The power of a machine or Archive on a network. It keeps count of the part that came from outside the cables:
 * a generator, a battery or another mod pushing in, or power it was saved or placed with. Only that part may go on into
 * the network, so power the cables brought is never handed straight back to them.
 */
public class NetworkEnergy extends SimpleEnergyHandler {
    private int own;
    private final OwnJournal ownJournal = new OwnJournal();

    public NetworkEnergy(int capacity) {
        super(capacity, capacity, capacity);
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        int inserted = super.insert(amount, transaction);
        if (inserted > 0) {
            ownJournal.updateSnapshots(transaction);
            own = Math.min(own + inserted, energy);
        }
        return inserted;
    }

    @Override
    public void set(int amount) {
        int before = energy;
        super.set(amount);
        own = energy > before ? own + energy - before : Math.min(own, energy);
    }

    /** Changes how much it can hold. What it holds stays, even above the new size, until the owner trims it. */
    public void resize(int newCapacity) {
        capacity = newCapacity;
        maxInsert = newCapacity;
        maxExtract = newCapacity;
    }

    /** The part that came from outside the cables. Running costs come out of the cables' part first. */
    public int own() {
        return Math.min(own, energy);
    }

    /** The network just gave it {@code amount}, through a cable or a touching machine: that part isn't its own. */
    public void fromNetwork(int amount) {
        own = Math.max(0, Math.min(own, energy) - amount);
    }

    /** It gave {@code amount} of its own power away. */
    void gave(int amount) {
        own = Math.max(0, Math.min(own, energy + amount) - amount);
    }

    private final class OwnJournal extends SnapshotJournal<Integer> {
        @Override
        protected Integer createSnapshot() {
            return own;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            own = snapshot;
        }
    }
}
