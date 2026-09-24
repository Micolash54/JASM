package dev.micolash.jasm.deck;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What the player's screen knows about an open Deck: combined contents of its wafers, charge, and each wafer
 * slot's status. Filled from server messages; never trusted by the server.
 */
public final class DeckView {
    private final Map<ItemResource, Long> contents = new LinkedHashMap<>();
    private int energy;
    private List<DeckStorage.SlotStatus> slots = List.of();
    private int version;

    /** Page 0 of a snapshot replaces everything; later pages add to it. */
    public void applySnapshotPage(int page, List<DeckPayloads.Entry> entries) {
        if (page == 0) {
            contents.clear();
        }
        apply(entries);
    }

    /** Changed counts; a count of 0 removes the entry. */
    public void apply(List<DeckPayloads.Entry> entries) {
        for (DeckPayloads.Entry entry : entries) {
            if (entry.count() <= 0) {
                contents.remove(entry.key());
            } else {
                contents.put(entry.key(), entry.count());
            }
        }
        version++;
    }

    public void applyStatus(int energy, List<DeckStorage.SlotStatus> slots) {
        this.energy = energy;
        this.slots = List.copyOf(slots);
        version++;
    }

    public Map<ItemResource, Long> contents() {
        return Collections.unmodifiableMap(contents);
    }

    public int energy() {
        return energy;
    }

    public List<DeckStorage.SlotStatus> slots() {
        return slots;
    }

    /** Goes up on every change, so the screen knows when to rebuild its grid. */
    public int version() {
        return version;
    }
}
