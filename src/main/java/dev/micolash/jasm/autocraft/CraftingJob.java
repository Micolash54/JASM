package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStackTemplate;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * One job on a Crafting Server: what was asked for, the crafts still to run, the crafts running right now, and where
 * its items are (a hidden record in the wafer store). The items never sit anywhere else: a running craft only takes
 * its ingredients and adds its result when it finishes, in one step. The one exception is a set sent into a machine
 * behind an Access Port: it leaves the record when it goes in, and what comes back is added as it arrives.
 */
public final class CraftingJob {
    public enum Phase implements StringRepresentable {
        /** Crafting. */
        CRAFTING,
        /** Asked to stop: no new crafts; the running ones finish. */
        CANCELLING,
        /** Done or stopped: everything left goes back to the requester. */
        RETURNING;

        public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One card and how many crafts of it are left. */
    public static final class Step {
        static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
                Card.CODEC.fieldOf("card").forGetter(s -> s.card),
                Codec.LONG.fieldOf("left").forGetter(s -> s.left),
                Codec.LONG.fieldOf("total").forGetter(s -> s.total))
                .apply(i, Step::new));

        final Card card;
        long left;
        final long total;

        Step(Card card, long left, long total) {
            this.card = card;
            this.left = left;
            this.total = total;
        }

        public Card card() {
            return card;
        }

        public long left() {
            return left;
        }

        public long total() {
            return total;
        }
    }

    /** A craft in progress: which step, how long to go, and the ingredients set aside for it (one per slot). */
    public static final class Running {
        static final Codec<Running> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("step").forGetter(r -> r.step),
                Codec.INT.fieldOf("ticks").forGetter(r -> r.ticks),
                ItemResource.OPTIONAL_CODEC.listOf().fieldOf("inputs").forGetter(r -> r.inputs))
                .apply(i, Running::new));

        final int step;
        int ticks;
        /** Nine entries; empty where the slot stays empty. */
        final List<ItemResource> inputs;

        Running(int step, int ticks, List<ItemResource> inputs) {
            this.step = step;
            this.ticks = ticks;
            this.inputs = List.copyOf(inputs);
        }
    }

    /**
     * One set of a processing card's ingredients sent into a machine: which step, through which Access Port, what
     * has yet to come back, and when it went. It holds a Processor slot until nothing is left to wait for.
     */
    public static final class Sent {
        static final Codec<Sent> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("step").forGetter(s -> s.step),
                BlockPos.CODEC.fieldOf("port").forGetter(s -> s.port),
                Direction.CODEC.optionalFieldOf("side", Direction.DOWN).forGetter(s -> s.side),
                ProcessingCard.Amount.CODEC.listOf().fieldOf("waiting").forGetter(s -> s.waiting),
                Codec.LONG.optionalFieldOf("since", 0L).forGetter(s -> s.since))
                .apply(i, Sent::new));

        final int step;
        final BlockPos port;
        /** Which side of the port the machine is on. */
        final Direction side;
        /** Still to come back; entries drop out as they arrive. */
        final List<ProcessingCard.Amount> waiting;
        /** Game time it was sent. */
        final long since;

        Sent(int step, BlockPos port, Direction side, List<ProcessingCard.Amount> waiting, long since) {
            this.step = step;
            this.port = port.immutable();
            this.side = side;
            this.waiting = new ArrayList<>(waiting);
            this.since = since;
        }

        /** Counts {@code amount} of {@code key} as arrived. Returns how many of them this set was waiting for. */
        long arrive(GridKey key, long amount) {
            for (int i = 0; i < waiting.size(); i++) {
                ProcessingCard.Amount wanted = waiting.get(i);
                if (key.equals(wanted.key())) {
                    long used = Math.min(amount, wanted.count());
                    int rest = (int) (wanted.count() - used);
                    if (rest <= 0) {
                        waiting.remove(i);
                    } else {
                        waiting.set(i, wanted.withCount(rest));
                    }
                    return used;
                }
            }
            return 0;
        }

        boolean done() {
            return waiting.isEmpty();
        }

        Machines.At at() {
            return new Machines.At(port, side);
        }

        /** How many of {@code key} this set still waits for. */
        long wants(GridKey key) {
            return waiting.stream().filter(a -> key.equals(a.key())).mapToLong(ProcessingCard.Amount::count).sum();
        }

        long arrive(ItemResource key, long amount) {
            return arrive(new GridKey.Item(key), amount);
        }

        long wants(ItemResource key) {
            return wants(new GridKey.Item(key));
        }
    }

    /** What a job is waiting on at a machine, for screens. */
    public record Waiting(String machine, GridKey what, long count, long ticks) {
        /** "Waiting on Energized Smelter: 1 × Charcoal (0:12)"; a fluid reads "250 mB Water". */
        public Component line() {
            long seconds = ticks / 20;
            String time = seconds / 60 + ":" + String.format("%02d", seconds % 60);
            if (what instanceof GridKey.Fluid fluid) {
                return Component.translatable("screen.jasm.server.waiting_fluid", machine, FluidAmounts.label(count),
                        fluid.resource().getHoverName(), time);
            }
            if (what instanceof GridKey.Material material) {
                return Component.translatable("screen.jasm.server.waiting_material", machine, count,
                        MaterialMarkerItem.of(material.key()).getHoverName(), time);
            }
            return Component.translatable("screen.jasm.server.waiting", machine, count, what.item().toStack(1).getHoverName(), time);
        }
    }

    public static final Codec<CraftingJob> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(j -> j.id),
            Codec.LONG.fieldOf("serial").forGetter(j -> j.serial),
            UUIDUtil.CODEC.fieldOf("record").forGetter(j -> j.recordId),
            UUIDUtil.CODEC.fieldOf("requester").forGetter(j -> j.requester),
            Codec.STRING.optionalFieldOf("requester_name", "").forGetter(j -> j.requesterName),
            UUIDUtil.CODEC.optionalFieldOf("deck").forGetter(j -> Optional.ofNullable(j.deck)),
            ItemStackTemplate.CODEC.optionalFieldOf("target").forGetter(j -> Optional.ofNullable(j.target)),
            Codec.LONG.optionalFieldOf("amount", 0L).forGetter(j -> j.amount),
            Step.CODEC.listOf().optionalFieldOf("steps", List.of()).forGetter(j -> j.steps),
            Running.CODEC.listOf().optionalFieldOf("running", List.of()).forGetter(j -> j.running),
            Sent.CODEC.listOf().optionalFieldOf("sent", List.of()).forGetter(j -> j.sent),
            Phase.CODEC.optionalFieldOf("phase", Phase.CRAFTING).forGetter(j -> j.phase),
            Codec.BOOL.optionalFieldOf("to_player", false).forGetter(j -> j.toPlayer),
            Codec.LONG.optionalFieldOf("fluid_serial", 0L).forGetter(j -> j.fluidSerial),
            UUIDUtil.CODEC.optionalFieldOf("fluid_record").forGetter(j -> Optional.ofNullable(j.fluidRecordId)))
            .apply(i, CraftingJob::new));

    final UUID id;
    final long serial;
    final UUID recordId;
    /** The job's second hidden record, for fluids; unset (0, null) when the job holds none. */
    final long fluidSerial;
    final @Nullable UUID fluidRecordId;
    final UUID requester;
    final String requesterName;
    final UUID deck;
    /** What the job makes, one of it; {@link #amount} says how many. */
    final ItemStackTemplate target;
    final long amount;
    final List<Step> steps;
    final List<Running> running;
    /** Sets of ingredients out in machines. */
    final List<Sent> sent;
    /** The results go into the requester's inventory rather than onto the Deck. */
    final boolean toPlayer;
    Phase phase;
    /** Why nothing is happening right now, for screens; not saved. */
    PauseReason pause = PauseReason.NONE;
    /** Set by each collection: a machine holds results of a material that the network's storage has no room for. */
    boolean noRoom;
    /** The machine the job has waited on longest, for screens; not saved. */
    @Nullable Waiting waiting;
    /** Ticks in a row with nothing running and nothing able to start; not saved. */
    int stuck;
    /** Each step's card with its live recipe, looked up once; not saved. */
    private final Map<Integer, Optional<CardRecipes.Resolved>> resolved = new HashMap<>();
    /** Which items each step's slots were found to accept; not saved. */
    private final Map<SlotKey, Boolean> accepted = new HashMap<>();

    private record SlotKey(int step, int slot, ItemResource key) {}

    private CraftingJob(UUID id, long serial, UUID recordId, UUID requester, String requesterName, Optional<UUID> deck,
            Optional<ItemStackTemplate> target, long amount, List<Step> steps, List<Running> running, List<Sent> sent, Phase phase,
            boolean toPlayer, long fluidSerial, Optional<UUID> fluidRecordId) {
        this.id = id;
        this.serial = serial;
        this.recordId = recordId;
        this.fluidSerial = fluidSerial;
        this.fluidRecordId = fluidRecordId.orElse(null);
        this.requester = requester;
        this.requesterName = requesterName;
        this.deck = deck.orElse(null);
        this.target = target.orElse(null);
        this.amount = amount;
        this.steps = new ArrayList<>(steps);
        this.running = new ArrayList<>(running);
        this.sent = new ArrayList<>(sent);
        this.phase = phase;
        this.toPlayer = toPlayer;
    }

    static CraftingJob start(UUID id, long serial, UUID recordId, UUID requester, String requesterName, UUID deck, ItemStackTemplate target,
            long amount, List<Step> steps, boolean toPlayer, long fluidSerial, Optional<UUID> fluidRecordId) {
        return new CraftingJob(id, serial, recordId, requester, requesterName, Optional.of(deck), Optional.of(target), amount, steps,
                List.of(), List.of(), Phase.CRAFTING, toPlayer, fluidSerial, fluidRecordId);
    }

    /** Whether the job keeps fluids in a record of their own. */
    boolean hasFluidRecord() {
        return fluidRecordId != null;
    }

    /** A job found in the list of running jobs but missing from its server (after a crash): it only returns its items. */
    static CraftingJob adopted(AutocraftState.Job entry) {
        return new CraftingJob(entry.id(), entry.serial(), entry.recordId(), entry.requester(), entry.requesterName(), entry.deck(),
                Optional.empty(), 0, List.of(), List.of(), List.of(), Phase.RETURNING, false, entry.fluidSerial(), entry.fluidRecordId());
    }

    /** Step {@code index}'s crafting card with its recipe, or null if the recipe is gone (or it is a processing card). */
    CardRecipes.@Nullable Resolved resolved(ServerLevel level, int index) {
        return resolved.computeIfAbsent(index, i -> steps.get(i).card instanceof RecipeCard card
                ? Optional.ofNullable(CardRecipes.resolve(level, card))
                : Optional.empty()).orElse(null);
    }

    /** Whether {@code key} may go in {@code slot} of step {@code step}'s card; remembered for the rest of the job. */
    boolean accepts(ServerLevel level, int step, CardRecipes.Resolved card, int slot, ItemResource key) {
        return accepted.computeIfAbsent(new SlotKey(step, slot, key), k -> card.accepts(level, slot, key));
    }

    static Step step(Card card, long crafts) {
        return new Step(card, crafts, crafts);
    }

    public UUID id() {
        return id;
    }

    public boolean toPlayer() {
        return toPlayer;
    }

    public UUID requester() {
        return requester;
    }

    public String requesterName() {
        return requesterName;
    }

    public UUID deck() {
        return deck;
    }

    public Phase phase() {
        return phase;
    }

    public ItemStackTemplate target() {
        return target;
    }

    public long amount() {
        return amount;
    }

    public List<Step> steps() {
        return steps;
    }

    /** Crafts in progress, in the server and out in machines. */
    public int runningCount() {
        return running.size() + sent.size();
    }

    /** Sets out in machines. */
    public int sentCount() {
        return sent.size();
    }

    public @Nullable Waiting waiting() {
        return waiting;
    }

    public PauseReason pause() {
        return pause;
    }

    /** How far along, from 0 to 1, by crafts finished. */
    public float progress() {
        long total = 0;
        long left = 0;
        for (Step step : steps) {
            total += step.total;
            left += step.left;
        }
        left += running.size() + sent.size();
        return total == 0 ? 1F : Math.clamp(1F - left / (float) total, 0F, 1F);
    }
}
