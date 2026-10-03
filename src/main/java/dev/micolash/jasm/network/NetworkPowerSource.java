package dev.micolash.jasm.network;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/** A Generator, Creative Battery or Power Acceptor. Cables take its power, but it belongs to nobody and never carries the network. */
public interface NetworkPowerSource {
    /** What the network may draw from: gives power, takes none in. Always the same object. */
    EnergyHandler networkOutput();

    /** A block beside it changed. */
    void neighboursChanged();
}
