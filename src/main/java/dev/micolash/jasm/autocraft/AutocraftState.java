package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.ArchiveRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Where every Encoding Terminal stands (so a paired Crafting Deck can find its network from anywhere) and every
 * running job (so a Crafting Server that loads without its job, after a crash, can find its items again). Written to
 * disk straight away whenever a job starts or ends.
 */
public final class AutocraftState extends SavedData {
    public record Pairing(UUID terminal, UUID player, UUID deck) {
        static final Codec<Pairing> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("terminal").forGetter(Pairing::terminal),
                        UUIDUtil.CODEC.fieldOf("player").forGetter(Pairing::player),
                        UUIDUtil.CODEC.fieldOf("deck").forGetter(Pairing::deck))
                .apply(i, Pairing::new));
    }

    public record Terminal(UUID id, ArchiveRecord.Placement placement) {
        static final Codec<Terminal> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(Terminal::id),
                        ArchiveRecord.Placement.CODEC.fieldOf("placement").forGetter(Terminal::placement))
                .apply(i, Terminal::new));
    }

    /** A running job: its hidden record of items, its server, and who asked for it with which Deck. */
    public record Job(UUID id, long serial, UUID recordId, ArchiveRecord.Placement server, UUID requester, String requesterName,
            Optional<UUID> deck, boolean finished, Optional<UUID> rule) {
        static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(Job::id),
                        Codec.LONG.fieldOf("serial").forGetter(Job::serial),
                        UUIDUtil.CODEC.fieldOf("record").forGetter(Job::recordId),
                        ArchiveRecord.Placement.CODEC.fieldOf("server").forGetter(Job::server),
                        UUIDUtil.CODEC.fieldOf("requester").forGetter(Job::requester),
                        Codec.STRING.optionalFieldOf("requester_name", "").forGetter(Job::requesterName),
                        UUIDUtil.CODEC.optionalFieldOf("deck").forGetter(Job::deck),
                        Codec.BOOL.optionalFieldOf("finished", false).forGetter(Job::finished),
                        UUIDUtil.CODEC.optionalFieldOf("rule").forGetter(Job::rule))
                .apply(i, Job::new));

        Job asFinished() {
            return new Job(id, serial, recordId, server, requester, requesterName, deck, true, rule);
        }
    }

    public static final Codec<AutocraftState> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Terminal.CODEC.listOf().optionalFieldOf("terminals", List.of()).forGetter(s -> List.copyOf(s.terminals.values())),
                    Job.CODEC.listOf().optionalFieldOf("jobs", List.of()).forGetter(s -> List.copyOf(s.jobs.values())),
                    Pairing.CODEC.listOf().optionalFieldOf("pairings", List.of()).forGetter(s -> List.copyOf(s.pairings.values())))
            .apply(i, AutocraftState::new));

    public static final SavedDataType<AutocraftState> TYPE = new SavedDataType<>(Jasm.id("autocraft"), AutocraftState::new, CODEC);

    private final Map<UUID, Terminal> terminals = new LinkedHashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Pairing> pairings = new LinkedHashMap<>();

    public AutocraftState() {}

    private AutocraftState(List<Terminal> terminals, List<Job> jobs, List<Pairing> pairings) {
        terminals.forEach(t -> this.terminals.put(t.id(), t));
        jobs.forEach(j -> this.jobs.put(j.id(), j));
        pairings.forEach(p -> this.pairings.put(p.deck(), p));
    }

    public static AutocraftState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<Terminal> terminal(UUID id) {
        return Optional.ofNullable(terminals.get(id));
    }

    /** Registers one Deck for this player on the connected group of terminals. The same Deck cannot stay paired to another player. */
    public void pair(UUID terminal, UUID player, UUID deck, List<UUID> connectedTerminals) {
        pairings.values().removeIf(p -> p.deck().equals(deck)
                || p.player().equals(player) && connectedTerminals.contains(p.terminal()));
        pairings.put(deck, new Pairing(terminal, player, deck));
        setDirty();
    }

    public boolean isPaired(UUID terminal, UUID player, UUID deck) {
        Pairing pairing = pairings.get(deck);
        return pairing != null && pairing.terminal().equals(terminal) && pairing.player().equals(player);
    }

    public boolean isActive(UUID terminal, UUID deck) {
        Pairing pairing = pairings.get(deck);
        return pairing != null && pairing.terminal().equals(terminal);
    }

    public Optional<UUID> pairedDeck(UUID terminal, UUID player) {
        return pairings.values().stream()
                .filter(p -> p.terminal().equals(terminal) && p.player().equals(player))
                .map(Pairing::deck).findFirst();
    }

    public Optional<Pairing> pairing(UUID deck) {
        return Optional.ofNullable(pairings.get(deck));
    }

    public Optional<Pairing> pairedPlayer(List<UUID> terminals, UUID player) {
        return pairings.values().stream().filter(p -> p.player().equals(player) && terminals.contains(p.terminal())).findFirst();
    }

    /** A same-owner merge keeps each player's most recently registered Deck. */
    public void mergePairings(List<UUID> terminals) {
        Map<UUID, UUID> latest = new LinkedHashMap<>();
        pairings.values().stream().filter(p -> terminals.contains(p.terminal())).forEach(p -> latest.put(p.player(), p.deck()));
        if (pairings.values().removeIf(p -> terminals.contains(p.terminal()) && !p.deck().equals(latest.get(p.player())))) {
            setDirty();
        }
    }

    public void unpairTerminal(UUID terminal) {
        if (pairings.values().removeIf(p -> p.terminal().equals(terminal))) {
            setDirty();
        }
    }

    public void placeTerminal(UUID id, ArchiveRecord.Placement placement) {
        Terminal old = terminals.put(id, new Terminal(id, placement));
        if (old == null || !old.placement().equals(placement)) {
            setDirty();
        }
    }

    /** The terminal left this spot (mined): keep nothing, a Deck can't reach a terminal that stands nowhere. */
    public void removeTerminal(UUID id, ArchiveRecord.Placement placement) {
        Terminal old = terminals.get(id);
        if (old != null && old.placement().equals(placement)) {
            terminals.remove(id);
            setDirty();
        }
    }

    public Optional<Job> job(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<Job> jobs() {
        return new ArrayList<>(jobs.values());
    }

    public void addJob(Job job) {
        jobs.put(job.id(), job);
        setDirty();
    }

    /**
     * The job is over. Its entry stays until its item record is safely on disk, so that a crash in between still finds
     * the items (see {@link Jobs#cleanUp}).
     */
    public void finishJob(UUID id) {
        Job job = jobs.get(id);
        if (job != null && !job.finished()) {
            jobs.put(id, job.asFinished());
            setDirty();
        }
    }

    public void removeJob(UUID id) {
        if (jobs.remove(id) != null) {
            setDirty();
        }
    }

    /** Writes this file now, so it is never older than the item records a job just changed. */
    public void saveNow(MinecraftServer server) {
        if (isDirty()) {
            server.getDataStorage().saveAndJoin();
        }
    }
}
