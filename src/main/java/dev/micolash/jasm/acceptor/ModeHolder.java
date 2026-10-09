package dev.micolash.jasm.acceptor;

/** Something with a Power Acceptor's mode, which its screen reads and switches. */
public interface ModeHolder {
    AcceptorMode mode();

    void setMode(AcceptorMode mode);
}
