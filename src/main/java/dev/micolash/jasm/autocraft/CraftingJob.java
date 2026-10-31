package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.transfer.item.ItemResource;
import dev.micolash.jasm.network.CableNetwork;
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

    /** Some of one item a step may still use. */
    record Use(ItemResource item, long count) {
        static final Codec<Use> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemResource.CODEC.fieldOf("item").forGetter(Use::item),
                Codec.LONG.fieldOf("count").forGetter(Use::count))
                .apply(i, Use::new));
    }

    /**
     * One card, how many crafts of it are left, and the items the plan gave it that it has yet to use. A step keeps to
     * its share, so an item one step takes in place of its card's own never goes missing for another. Jobs saved
     * before this have no share and pick from what the job holds, as they always did.
     */
    public static final class Step {
        static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
                Card.CODEC.fieldOf("card").forGetter(s -> s.card),
                Codec.LONG.fieldOf("left").forGetter(s -> s.left),
                Codec.LONG.fieldOf("total").forGetter(s -> s.total),
                Use.CODEC.listOf().optionalFieldOf("uses").forGetter(s -> Optional.ofNullable(s.uses).map(Step::list)))
                .apply(i, (card, left, total, uses) -> new Step(card, left, total, uses.map(Step::map).orElse(null))));

        final Card card;
        long left;
        final long total;
        /** What is left of the plan's share, by item; null for a job planned before shares were kept. */
        final @Nullable Map<ItemResource, Long> uses;

        Step(Card card, long left, long total, @Nullable Map<ItemResource, Long> uses) {
            this.card = card;
            this.left = left;
            this.total = total;
            this.uses = uses;
        }

        private static List<Use> list(Map<ItemResource, Long> uses) {
            return uses.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> new Use(e.getKey(), e.getValue())).toList();
        }

        private static Map<ItemResource, Long> map(List<Use> uses) {
            Map<ItemResource, Long> map = new LinkedHashMap<>();
            uses.forEach(use -> map.merge(use.item(), use.count(), Long::sum));
            return map;
        }

        /** How much of {@code key} is left of the step's share; 0 for a job planned before shares were kept. */
        long share(ItemResource key) {
            return uses == null ? 0 : uses.getOrDefault(key, 0L);
        }

        /** Counts {@code amount} of {@code key} as used, or as given back when negative. */
        void use(ItemResource key, long amount) {
            if (uses != null && (amount < 0 || uses.containsKey(key))) {
                uses.merge(key, -amount, (a, b) -> Math.max(0, a + b));
            }
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
     * One set of a processing card's ingredients sent into a machine: which step, through which Access Port, the machine
     * that took it and its name then, what has yet to come back, and when it went. It holds a Processor slot until
     * nothing is left to wait for.
     */
    public static final class Sent {
        static final Codec<Sent> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("step").forGetter(s -> s.step),
                BlockPos.CODEC.fieldOf("port").forGetter(s -> s.port),
                Direction.CODEC.optionalFieldOf("side", Direction.DOWN).forGetter(s -> s.side),
                ProcessingCard.Amount.CODEC.listOf().fieldOf("waiting").forGetter(s -> s.waiting),
                Codec.LONG.optionalFieldOf("since", 0L).forGetter(s -> s.since),
                // sets sent before these were kept have neither
                BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("machine").forGetter(s -> Optional.ofNullable(s.machine)),
                ComponentSerialization.CODEC.optionalFieldOf("machine_name").forGetter(s -> Optional.ofNullable(s.name)))
                .apply(i, (step, port, side, waiting, since, machine, name) -> new Sent(step, port, side, waiting, since,
                        machine.orElse(null), name.orElse(null))));

        final int step;
        final BlockPos port;
        /** Which side of the port the machine is on. */
        final Direction side;
        /** Still to come back; entries drop out as they arrive. */
        final List<ProcessingCard.Amount> waiting;
        /** Game time it was sent. */
        final long since;
        /** The machine that took the set, and the name it had then; null on sets saved before they were kept. */
        final @Nullable Block machine;
        final @Nullable Component name;

        Sent(int step, BlockPos port, Direction side, List<ProcessingCard.Amount> waiting, long since, @Nullable Block machine,
                @Nullable Component name) {
            this.step = step;
            this.port = port.immutable();
            this.side = side;
            this.waiting = new ArrayList<>(waiting);
            this.since = since;
            this.machine = machine;
            this.name = name;
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

        /** How much of the card's main result has come back for this set; extras and byproducts don't count. */
        long arrived(ProcessingCard card) {
            ProcessingCard.Amount main = card.main();
            if (main.isEmpty()) {
                return 0;
            }
            // what the set waits for of that kind started as every output of it, the main one filled first
            long expected = card.outputs().stream().filter(a -> !a.isEmpty() && main.key().equals(a.key()))
                    .mapToLong(ProcessingCard.Amount::count).sum();
            return Math.clamp(expected - wants(main.key()), 0L, main.count());
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

    /**
     * One kind of craft running right now, for screens: what it makes, how much of it the job has made out of how much,
     * how many run at once, and the machine's name when they are out in one (empty when they run in the server).
     */
    public record Now(ItemResource what, long made, long total, int running, Optional<Component> machine) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Now> STREAM_CODEC = StreamCodec.composite(
                ItemResource.STREAM_CODEC, Now::what,
                ByteBufCodecs.VAR_LONG, Now::made,
                ByteBufCodecs.VAR_LONG, Now::total,
                ByteBufCodecs.VAR_INT, Now::running,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), Now::machine,
                Now::new);
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
            UUIDUtil.CODEC.optionalFieldOf("fluid_record").forGetter(j -> Optional.ofNullable(j.fluidRecordId)),
            PauseReason.CODEC.optionalFieldOf("ended", PauseReason.NONE).forGetter(j -> j.ended))
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
    /**
     * Why part of the job stopped for good (a set's port was removed), kept and saved until the job is over so its
     * screens can still say so once nothing else holds it up.
     */
    PauseReason ended;
    /** Set by each collection: a machine holds results of a material that the network's storage has no room for. */
    boolean noRoom;
    /** The machine the job has waited on longest, for screens; not saved. */
    @Nullable Waiting waiting;
    /** Ticks in a row with nothing running and nothing able to start; not saved. */
    int stuck;
    /** What the machines last said was wrong, and for how many ticks it has held; not saved. */
    PauseReason machineReason = PauseReason.NONE;
    int machineTicks;
    /** Each step's card with its live recipe, looked up once; not saved. */
    private final Map<Integer, Optional<CardRecipes.Resolved>> resolved = new HashMap<>();
    /** Which items each step's slots were found to accept; not saved. */
    private final Map<SlotKey, Boolean> accepted = new HashMap<>();
    /** Each step's machine recipe, once found; not saved. */
    private final Map<Integer, MachineRecipes.Match> machineRecipes = new HashMap<>();
    /** When to look again for a step's machine recipe that couldn't be found; not saved. */
    private final Map<Integer, Long> machineRetry = new HashMap<>();
    /** Steps a machine turned a planned item away from; not saved. */
    private final Set<Integer> turnedAway = new HashSet<>();

    /** Which item each step makes from copies of itself, if any; not saved. */
    private final Map<Integer, Optional<Growing>> growing = new HashMap<>();

    private record SlotKey(int step, int slot, ItemResource key) {}

    /** An item a step makes from copies of itself, and how many of them one craft takes. */
    record Growing(ItemResource item, long perCraft) {}

    /**
     * The item step {@code index}'s card makes that its own slots also take (a template copied with diamonds), with how
     * many one craft takes; null when it takes none. Found once for the rest of the job.
     */
    @Nullable Growing growing(ServerLevel level, int index) {
        return growing.computeIfAbsent(index, i -> {
            Card card = steps.get(i).card;
            if (card instanceof ProcessingCard processing) {
                ProcessingCard.Amount main = processing.main();
                if (main.isEmpty() || main.isFluid() || main.isMaterial()) return Optional.empty();
                long takes = processing.usedInputs().stream().filter(a -> !a.isFluid() && !a.isMaterial() && main.item().equals(a.item()))
                        .mapToLong(ProcessingCard.Amount::count).sum();
                return takes > 0 ? Optional.of(new Growing(main.item(), takes)) : Optional.empty();
            }
            CardRecipes.Resolved recipe = resolved(level, i);
            if (recipe == null) return Optional.empty();
            long takes = 0;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack encoded = recipe.encoded(slot);
                if (!encoded.isEmpty() && ItemResource.of(encoded).equals(recipe.outputKey())) takes++;
            }
            return takes > 0 ? Optional.of(new Growing(recipe.outputKey(), takes)) : Optional.empty();
        }).orElse(null);
    }

    private CraftingJob(UUID id, long serial, UUID recordId, UUID requester, String requesterName, Optional<UUID> deck,
            Optional<ItemStackTemplate> target, long amount, List<Step> steps, List<Running> running, List<Sent> sent, Phase phase,
            boolean toPlayer, long fluidSerial, Optional<UUID> fluidRecordId, PauseReason ended) {
        this.id = id;
        this.ended = ended;
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
                List.of(), List.of(), Phase.CRAFTING, toPlayer, fluidSerial, fluidRecordId, PauseReason.NONE);
    }

    /** Whether the job keeps fluids in a record of their own. */
    boolean hasFluidRecord() {
        return fluidRecordId != null;
    }

    /** A job found in the list of running jobs but missing from its server (after a crash): it only returns its items. */
    static CraftingJob adopted(AutocraftState.Job entry) {
        return new CraftingJob(entry.id(), entry.serial(), entry.recordId(), entry.requester(), entry.requesterName(), entry.deck(),
                Optional.empty(), 0, List.of(), List.of(), List.of(), Phase.RETURNING, false, entry.fluidSerial(), entry.fluidRecordId(),
                PauseReason.NONE);
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

    /**
     * Step {@code index}'s machine recipe, if its card is a processing card whose recipe JASM knows. Looked up once a
     * second until it is found (a machine may stand in an unloaded spot for a while), then kept for the rest of the job.
     */
    MachineRecipes.@Nullable Match machineRecipe(ServerLevel level, @Nullable CableNetwork network, int index) {
        MachineRecipes.Match known = machineRecipes.get(index);
        long now = level.getGameTime();
        if (known == null && steps.get(index).card instanceof ProcessingCard card && now >= machineRetry.getOrDefault(index, 0L)) {
            known = MachineRecipes.of(level, Machines.located(level, network, card));
            if (known != null) {
                machineRecipes.put(index, known);
            } else {
                machineRetry.put(index, now + 20);
            }
        }
        return known;
    }

    /**
     * Whether used input slot {@code slot} of step {@code step}'s processing card takes {@code key} in place of its own
     * item; remembered for the rest of the job.
     */
    boolean takes(ServerLevel level, int step, MachineRecipes.Match recipe, int slot, ItemResource key) {
        return accepted.computeIfAbsent(new SlotKey(step, -1 - slot, key), k -> recipe.accepts(level, slot, key));
    }

    /** A machine turned {@code key} away from used input slot {@code slot} of step {@code step}: it isn't tried there again. */
    void refuse(int step, int slot, ItemResource key) {
        accepted.put(new SlotKey(step, -1 - slot, key), false);
        turnedAway.add(step);
    }

    /** Whether a machine turned away an item planned for step {@code step}. */
    boolean turnedAway(int step) {
        return turnedAway.contains(step);
    }

    /** A step with no share: it picks from what the job holds, as steps did before shares were kept. */
    static Step step(Card card, long crafts) {
        return new Step(card, crafts, crafts, null);
    }

    /** A step of a new job; {@code uses} is every item the plan gave it. */
    static Step step(Card card, long crafts, Map<ItemResource, Long> uses) {
        return new Step(card, crafts, crafts, new LinkedHashMap<>(uses));
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

    /** What screens show: what holds the job up now, or else why part of it stopped for good. */
    public PauseReason pause() {
        return pause != PauseReason.NONE ? pause : ended;
    }

    /** Why part of the job stopped for good; {@link PauseReason#NONE} while nothing has. */
    public PauseReason ended() {
        return ended;
    }

    /** What is crafting right now, one entry per step with crafts running, the final item first and its ingredients after. */
    public List<Now> now() {
        int[] running = new int[steps.size()];
        // results of sets still out that have already come back
        long[] arrived = new long[steps.size()];
        Component[] machine = new Component[steps.size()];
        for (Running craft : this.running) {
            if (craft.step < running.length) running[craft.step]++;
        }
        for (Sent set : sent) {
            if (set.step >= running.length) continue;
            running[set.step]++;
            if (steps.get(set.step).card instanceof ProcessingCard card) arrived[set.step] += set.arrived(card);
            if (machine[set.step] == null && steps.get(set.step).card instanceof ProcessingCard card) {
                // The name the machine had when the set went in; looking it up in the world each time would cost more. A
                // set saved before names were kept falls back on the name an older card gave its machine.
                String old = set.name != null ? "" : card.machines().stream().filter(m -> m.at().equals(set.at()))
                        .map(ProcessingCard.Machine::name).findFirst().orElse("");
                machine[set.step] = set.name != null ? set.name
                        : old.isBlank() ? Component.translatable("screen.jasm.server.machine") : Component.literal(old);
            }
        }
        List<Now> now = new ArrayList<>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (running[i] == 0) continue;
            Step step = steps.get(i);
            long each = step.card instanceof ProcessingCard card ? card.main().count() : step.card.result().getCount();
            long made = Math.min(Math.max(0, step.total - step.left - running[i]) * each + arrived[i], step.total * each);
            now.add(new Now(ItemResource.of(step.card.result()), made, step.total * each, running[i],
                    Optional.ofNullable(machine[i])));
        }
        return now;
    }

    /** Where the machines are that the job has sets waiting in now, to light them up; empty when none is out. */
    public List<BlockPos> machines() {
        List<BlockPos> found = new ArrayList<>();
        for (Sent set : sent) {
            BlockPos at = set.port.relative(set.side);
            if (!set.done() && !found.contains(at)) found.add(at);
        }
        return found;
    }

    /** How far along, from 0 to 1, by crafts finished; a set out in a machine counts for the share of its main result back. */
    public float progress() {
        long total = 0;
        float left = 0;
        for (Step step : steps) {
            total += step.total;
            left += step.left;
        }
        left += running.size();
        for (Sent set : sent) {
            left += 1;
            if (set.step < steps.size() && steps.get(set.step).card instanceof ProcessingCard card && card.main().count() > 0) {
                left -= set.arrived(card) / (float) card.main().count();
            }
        }
        return total == 0 ? 1F : Math.clamp(1F - left / total, 0F, 1F);
    }
}
