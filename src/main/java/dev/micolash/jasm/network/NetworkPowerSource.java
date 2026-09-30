package dev.micolash.jasm.network;

/** A Generator or Creative Battery that carries the network through its block. */
public interface NetworkPowerSource {
    SourceOwnership networkOwnership();
}
