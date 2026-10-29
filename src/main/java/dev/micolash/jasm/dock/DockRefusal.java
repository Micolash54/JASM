package dev.micolash.jasm.dock;

import java.util.Locale;

/** Why the dock turned a player's request down; {@link #NONE} when it went ahead. Shown in the dock's panel. */
public enum DockRefusal {
    NONE,
    /** The player isn't the network's owner or trusted on it. */
    NO_ACCESS,
    NOT_A_DECK,
    /** The dock already holds a Deck. */
    OCCUPIED,
    /** The Deck isn't linked to this network (pair it at one of its Network Encoders first). */
    NOT_LINKED_HERE,
    /** The Deck's player isn't trusted on this network. */
    NOT_TRUSTED_HERE,
    /** This Deck already sits in another dock. */
    DOCKED_ELSEWHERE,
    /** There's no Deck in the dock. */
    EMPTY,
    /** Locked: only the Deck's owner (or the network's owner) may take it out. */
    LOCKED,
    /** Only the Deck's owner (or the network's owner) may lock or unlock it. */
    NOT_YOURS,
    /** The Deck in the dock changed since the panel showed it. */
    CHANGED,
    /** No room in the player's inventory. */
    INVENTORY_FULL;

    public String key() {
        return "screen.jasm.docking_station.refused." + name().toLowerCase(Locale.ROOT);
    }
}
