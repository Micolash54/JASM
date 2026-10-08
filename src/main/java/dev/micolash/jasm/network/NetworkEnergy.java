package dev.micolash.jasm.network;

import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/** The power of a machine or Archive on a network. Its size can change when the machine's setup does. */
public class NetworkEnergy extends SimpleEnergyHandler {
    public NetworkEnergy(int capacity) {
        super(capacity, capacity, capacity);
    }

    /** Changes how much it can hold. What it holds stays, even above the new size, until the owner trims it. */
    public void resize(int newCapacity) {
        capacity = newCapacity;
        maxInsert = newCapacity;
        maxExtract = newCapacity;
    }
}
