package dev.micolash.jasm.deck;

import dev.micolash.jasm.autocraft.CraftPayloads;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * What the player's screen knows about an open Deck: combined contents of its wafers, charge, and each wafer
 * slot's status. Filled from server messages; never trusted by the server.
 */
public final class DeckView {
    private final Map<ItemResource, Long> contents = new LinkedHashMap<>();
    private int energy;
    private List<DeckStorage.SlotStatus> slots = List.of();
    private int version;
    /** 0 not paired, 1 network out of reach, 2 reachable. */
    private int network;
    private Set<ItemResource> craftable = Set.of();
    private List<CraftPayloads.JobView> jobs = List.of();
    private CraftPayloads.@Nullable Answer answer;
    private int answerVersion;
    private Map<UUID, String> stalled = Map.of();

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

    public void applyCraftStatus(int network, Set<ItemResource> craftable, List<CraftPayloads.JobView> jobs, Map<UUID, String> stalled) {
        boolean listChanged = !craftable.equals(this.craftable);
        this.stalled = Map.copyOf(stalled);
        this.network = network;
        this.craftable = craftable;
        this.jobs = List.copyOf(jobs);
        if (listChanged) {
            version++;
        }
    }

    /** The Deck's rules that can't go ahead right now, with the message saying why. */
    public Map<UUID, String> stalled() {
        return stalled;
    }

    public void applyAnswer(CraftPayloads.Answer answer) {
        this.answer = answer;
        answerVersion++;
    }

    public int network() {
        return network;
    }

    public Set<ItemResource> craftable() {
        return craftable;
    }

    public List<CraftPayloads.JobView> jobs() {
        return jobs;
    }

    /** The server's last answer about a request, or null before the first. */
    public CraftPayloads.@Nullable Answer answer() {
        return answer;
    }

    /** Goes up with every new answer. */
    public int answerVersion() {
        return answerVersion;
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
