package dev.micolash.jasm.deck;

import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.core.MaterialKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * What the player's screen knows about an open Deck: combined contents of its wafers, charge, and each wafer
 * slot's status. Filled from server messages; never trusted by the server.
 */
public final class DeckView {
    private final Map<ItemResource, Long> contents = new LinkedHashMap<>();
    private final Map<FluidResource, Long> fluids = new LinkedHashMap<>();
    private final Map<MaterialKey, Long> materials = new LinkedHashMap<>();
    /** How much of each total sits in the network's storage blocks rather than on wafers. */
    private final Map<ItemResource, Long> chest = new LinkedHashMap<>();
    private final Map<FluidResource, Long> fluidChest = new LinkedHashMap<>();
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
    /** The last "Empty into other wafers" result, until the wafer window closes. */
    private DeckPayloads.@Nullable Emptied emptied;
    private DeckPayloads.@Nullable EmptyProgress emptying;
    private long emptyingSeen;
    /** If no news comes for this long, the window unlocks again. */
    private static final long EMPTYING_SILENCE_MS = 5_000;

    public void setEmptying(DeckPayloads.EmptyProgress progress) {
        emptying = progress;
        emptyingSeen = System.currentTimeMillis();
        emptied = null;
    }

    /** The running emptying for {@code slot}, or null when there is none. */
    public DeckPayloads.@Nullable EmptyProgress emptying(int slot) {
        if (emptying == null || emptying.slot() != slot) {
            return null;
        }
        if (System.currentTimeMillis() - emptyingSeen > EMPTYING_SILENCE_MS) {
            emptying = null;
        }
        return emptying;
    }

    public DeckPayloads.@Nullable Emptied emptied() {
        return emptied;
    }

    public void setEmptied(DeckPayloads.@Nullable Emptied emptied) {
        this.emptied = emptied;
        if (emptied != null) {
            emptying = null;
        }
    }

    /** Page 0 of a snapshot replaces everything; later pages add to it. */
    public void applySnapshotPage(int page, List<DeckPayloads.Entry> entries) {
        if (page == 0) {
            contents.clear();
            chest.clear();
        }
        apply(entries);
    }

    /** Changed counts; a count of 0 removes the entry. */
    public void apply(List<DeckPayloads.Entry> entries) {
        for (DeckPayloads.Entry entry : entries) {
            if (entry.count() <= 0) {
                contents.remove(entry.key());
                chest.remove(entry.key());
            } else {
                contents.put(entry.key(), entry.count());
                if (entry.chest() > 0) chest.put(entry.key(), entry.chest());
                else chest.remove(entry.key());
            }
        }
        version++;
    }

    /** Page 0 of a fluid snapshot replaces every fluid; later pages add to it. */
    public void applyFluidSnapshotPage(int page, List<DeckPayloads.FluidEntry> entries) {
        if (page == 0) {
            fluids.clear();
            fluidChest.clear();
        }
        applyFluids(entries);
    }

    /** Changed amounts; an amount of 0 removes the entry. */
    public void applyFluids(List<DeckPayloads.FluidEntry> entries) {
        for (DeckPayloads.FluidEntry entry : entries) {
            if (entry.amount() <= 0) {
                fluids.remove(entry.key());
                fluidChest.remove(entry.key());
            } else {
                fluids.put(entry.key(), entry.amount());
                if (entry.chest() > 0) fluidChest.put(entry.key(), entry.chest());
                else fluidChest.remove(entry.key());
            }
        }
        version++;
    }

    public void applyMaterialSnapshotPage(int page, List<DeckPayloads.MaterialEntry> entries) {
        if (page == 0) {
            materials.clear();
        }
        applyMaterials(entries);
    }

    public void applyMaterials(List<DeckPayloads.MaterialEntry> entries) {
        for (DeckPayloads.MaterialEntry entry : entries) {
            if (entry.amount() <= 0) materials.remove(entry.key());
            else materials.put(entry.key(), entry.amount());
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

    /** Millibuckets of each fluid on the Deck's fluid wafers. */
    public Map<FluidResource, Long> fluids() {
        return Collections.unmodifiableMap(fluids);
    }

    /** Other mods' materials in the network's storage blocks. Never on wafers. */
    public Map<MaterialKey, Long> materials() {
        return Collections.unmodifiableMap(materials);
    }

    /** How many of {@code key} sit in the network's storage blocks rather than on wafers. */
    public long chestOf(ItemResource key) {
        return chest.getOrDefault(key, 0L);
    }

    /** Millibuckets of {@code key} that sit in the network's storage blocks rather than on wafers. */
    public long chestFluidOf(FluidResource key) {
        return fluidChest.getOrDefault(key, 0L);
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
