package dev.micolash.jasm.network;

/** A Generator, Creative Battery or Power Acceptor. It pushes power into what it touches, but it belongs to nobody and never carries the network. */
public interface NetworkPowerSource {
    /** A block beside it changed. */
    void neighboursChanged();
}
