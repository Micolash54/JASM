package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.registry.JasmTriggers;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/** Running a server's job: starting crafts, sending sets to machines, and taking results back in. */
final class JobRunner {
    private JobRunner() {}

    /** Ticks between attempts to hand results back. */
    static final int DELIVER_EVERY = 20;
    /** Ticks with nothing running and nothing able to start before a job gives up and returns what it has. */
    static final int STUCK_TICKS = 100;

    /** The cards found on a network this tick. Weak by network, so a forgotten network takes its entry with it. */
    private static final Map<CableNetwork, Cards> CARDS = new WeakHashMap<>();

    private record Cards(long tick, Set<Card> cards) {}

    /**
     * Every card on the network, whoever may use them: what a running job may keep using. Found once per network per
     * game tick; racks can change without the network seeing it, so it is not kept longer.
     */
    static Set<Card> allCards(ServerLevel level, CableNetwork network) {
        long now = level.getGameTime();
        Cards known = CARDS.get(network);
        if (known != null && known.tick() == now) {
            return known.cards();
        }
        Set<Card> cards = new HashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            cards.addAll(rack.cards());
        }
        CARDS.put(network, new Cards(now, cards));
        return cards;
    }

    /** One tick of a server's job. {@code powered} is whether the server paid for this tick. */
    static void tick(ServerLevel level, CraftingServerBlockEntity server, boolean powered) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.ifOpen(level.getServer());
        if (job == null || store == null) {
            return;
        }
        WaferRecord record = record(store, job);
        if (record == null) {
            // The record is gone (it can't be read): nothing to craft with or return. Say so and let the server go.
            Jasm.LOGGER.error("Crafting job {} lost its item record #{}; ending it", job.id, job.serial);
            JobReturns.finish(level, server, job);
            return;
        }
        if (job.phase == CraftingJob.Phase.RETURNING) {
            // The reason it waits is set by each delivery attempt and kept until the next, so screens see it steadily.
            if (level.getGameTime() % DELIVER_EVERY == 0) {
                JobReturns.deliver(level, server, job, record, store);
            }
            return;
        }
        if (!powered) {
            if (level.getGameTime() % DELIVER_EVERY == 0 && JobReturns.deliverTarget(level, server, job, record, store)) server.setChanged();
            job.pause = server.stopped() ? PauseReason.NETWORK_FULL : PauseReason.NO_POWER;
            return;
        }
        CableNetwork network = Networks.at(level, server.getBlockPos());
        Set<Card> cards = network == null ? Set.of() : allCards(level, network);
        WaferRecord fluids = fluidRecord(store, job);
        boolean changed = finishCrafts(level, job, record, store);
        if (job.sent.isEmpty()) {
            job.noRoom = false;
        }
        changed |= collect(level, network, job, record, fluids, store);
        if (job.phase == CraftingJob.Phase.CANCELLING && !job.sent.isEmpty()) {
            // Whatever is out in machines stays there; the job stops waiting for it.
            release(level, job);
            changed = true;
        }
        boolean started = false;
        boolean missingCard = false;
        // A machine that can't be used right now: the job waits for it rather than giving up.
        Wait machineBlocked = Wait.NONE;
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack requesterDeck = requester == null ? ItemStack.EMPTY : JobReturns.findDeck(requester, job.deck);
        boolean dimensionBlocked = !requesterDeck.isEmpty()
                && (!DeckItem.worksIn(requesterDeck, requester.level()) || !DeckItem.worksIn(requesterDeck, level));
        if (job.phase == CraftingJob.Phase.CRAFTING && dimensionBlocked) machineBlocked = Wait.DIMENSION;
        if (job.phase == CraftingJob.Phase.CRAFTING && !dimensionBlocked) {
            int free = server.parallel() - job.running.size() - job.sent.size();
            Map<ItemResource, Long> reserved = reserved(job);
            Map<ItemResource, Long> seeds = seeds(level, job);
            for (int i = 0; i < job.steps.size() && free > 0; i++) {
                CraftingJob.Step step = job.steps.get(i);
                if (step.left <= 0) {
                    continue;
                }
                if (!cards.contains(step.card)) {
                    missingCard = true;
                    continue;
                }
                Map<ItemResource, Long> keep = keepFor(level, job, i, seeds);
                if (step.card instanceof ProcessingCard processing) {
                    Sending sending = send(level, network, job, i, processing, record, fluids, store, reserved, keep, free);
                    free -= sending.sent();
                    started |= sending.sent() > 0;
                    if (machineBlocked == Wait.NONE) {
                        machineBlocked = sending.blocked();
                    }
                    continue;
                }
                CardRecipes.Resolved card = job.resolved(level, i);
                while (free > 0 && step.left > 0 && card != null) {
                    List<ItemResource> inputs = chooseInputs(level, job, i, card, record, reserved, keep);
                    if (inputs == null) {
                        break;
                    }
                    inputs.forEach(key -> {
                        if (!key.isEmpty()) {
                            reserved.merge(key, 1L, Long::sum);
                            step.use(key, 1);
                        }
                    });
                    job.running.add(new CraftingJob.Running(i, JasmConfig.CRAFT_TICKS.getAsInt(), inputs));
                    step.left--;
                    free--;
                    started = true;
                }
            }
        }
        if (changed || level.getGameTime() % DELIVER_EVERY == 0) {
            changed |= JobReturns.deliverTarget(level, server, job, record, store);
        }
        if (started || changed) {
            server.setChanged();
        }
        boolean idle = job.running.isEmpty() && job.sent.isEmpty();
        PauseReason machineReason = held(job, idle ? machineBlocked : halted(level, job) ? Wait.HALTED : Wait.NONE);
        job.pause = job.noRoom ? PauseReason.NO_ROOM : idle && missingCard ? PauseReason.NO_CARD : machineReason;
        job.waiting = waiting(level, network, job);
        boolean stepsLeft = job.steps.stream().anyMatch(s -> s.left > 0);
        if (machineBlocked != Wait.NONE) {
            job.stuck = 0;
        }
        if (idle) {
            if (job.phase == CraftingJob.Phase.CANCELLING || !stepsLeft) {
                if (job.phase == CraftingJob.Phase.CRAFTING && requester != null) JasmTriggers.AUTOCRAFT_DONE.get().trigger(requester);
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = PauseReason.NONE;
                server.setChanged();
            } else if (!missingCard && machineBlocked == Wait.NONE && !started && ++job.stuck >= STUCK_TICKS) {
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = PauseReason.NONE;
                server.setChanged();
            }
        } else {
            job.stuck = 0;
        }
    }

    /** Crafts whose time is up: their ingredients go, their results come, in one step. Returns whether any finished. */
    static boolean finishCrafts(ServerLevel level, CraftingJob job, WaferRecord record, WaferStore store) {
        boolean any = false;
        for (int r = 0; r < job.running.size(); r++) {
            CraftingJob.Running craft = job.running.get(r);
            if (--craft.ticks > 0) {
                continue;
            }
            job.running.remove(r--);
            any = true;
            CardRecipes.Resolved card = job.resolved(level, craft.step);
            NonNullList<ItemStack> inputs = NonNullList.withSize(9, ItemStack.EMPTY);
            Map<ItemResource, Long> needed = new HashMap<>();
            for (int i = 0; i < 9; i++) {
                ItemResource key = craft.inputs.get(i);
                if (!key.isEmpty()) {
                    inputs.set(i, key.toStack(1));
                    needed.merge(key, 1L, Long::sum);
                }
            }
            boolean present = needed.entrySet().stream().allMatch(e -> record.count(e.getKey()) >= e.getValue());
            if (card == null || !present || !card.makes(level, inputs)) {
                // Something changed underneath (a crash rewound the items, or the recipe changed): try that craft again later.
                CraftingJob.Step step = job.steps.get(craft.step);
                step.left++;
                needed.forEach((key, n) -> step.use(key, -n));
                continue;
            }
            CraftingInput input = CraftingInput.of(3, 3, inputs);
            ItemStack made = card.recipe().assemble(input);
            NonNullList<ItemStack> left = card.recipe().getRemainingItems(input);
            needed.forEach((key, n) -> store.extract(record, key, n, false, null));
            store.insert(record, ItemResource.of(made), made.getCount(), false, null);
            for (ItemStack remainder : left) {
                if (!remainder.isEmpty()) {
                    store.insert(record, ItemResource.of(remainder), remainder.getCount(), false, null);
                }
            }
        }
        return any;
    }

    // --- machines ---

    /** Ticks a machine's problem must hold before screens show it, so one refused try never flashes a red line. */
    static final int SHOW_AFTER = 5;

    /** Why a step's ingredients are there but no set could go, with what screens say about it. */
    enum Wait {
        NONE(PauseReason.NONE),
        /** Normal work: Blocking Mode keeps the next set back until the machine has used the last. Nothing is shown. */
        WORKING(PauseReason.NONE),
        /** A port wasn't due to move anything yet, so nothing was learned: what was last found still stands. */
        UNTRIED(PauseReason.NONE),
        DIMENSION(PauseReason.DIMENSION_UPGRADE),
        NO_MATERIAL(PauseReason.NO_MATERIAL),
        MISSING(PauseReason.NO_MACHINE),
        UNLOADED(PauseReason.MACHINE_UNLOADED),
        SIDE(PauseReason.MACHINE_SIDE),
        PORT_OFF(PauseReason.PORT_OFF),
        IN_USE(PauseReason.MACHINE_IN_USE),
        /** A set is out in a JASM machine that has no power or is stopped by its Redstone Upgrade. */
        HALTED(PauseReason.MACHINE_OFF),
        /** Full, or won't take these items: another mod's machine says the same for both. */
        FULL(PauseReason.MACHINE_BUSY);

        final PauseReason shown;

        Wait(PauseReason shown) {
            this.shown = shown;
        }

        /** Normal work anywhere hides the problems: the job isn't stopped. Otherwise the first problem met stands. */
        Wait and(Wait other) {
            if (this == WORKING || other == WORKING) return WORKING;
            if (this == UNTRIED || other == UNTRIED) return UNTRIED;
            return this != NONE ? this : other;
        }
    }

    /** What screens show for the machines this tick: a problem only once it has held for {@link #SHOW_AFTER} ticks. */
    private static PauseReason held(CraftingJob job, Wait wait) {
        PauseReason now = wait == Wait.UNTRIED ? job.machineReason : wait.shown;
        if (now != job.machineReason) {
            job.machineReason = now;
            job.machineTicks = 0;
        } else if (job.machineTicks < SHOW_AFTER) {
            job.machineTicks++;
        }
        return job.machineTicks >= SHOW_AFTER ? now : PauseReason.NONE;
    }

    /** Whether a machine holding one of the job's sets can't work on it: only JASM machines can tell. */
    private static boolean halted(ServerLevel level, CraftingJob job) {
        for (CraftingJob.Sent set : job.sent) {
            BlockPos machine = set.port.relative(set.side);
            if (level.isLoaded(machine) && level.getBlockEntity(machine) instanceof MachineBlockEntity jasm && jasm.halted()) {
                return true;
            }
        }
        return false;
    }

    /** How sending went: how many sets went, and, when the ingredients were there but nothing could go, why. */
    private record Sending(int sent, Wait blocked) {}

    /** Why the machine at {@code at} can't be reached: not loaded, gone, or a grid side that lets nothing in. */
    private static Wait unreachable(ServerLevel level, @Nullable CableNetwork network, Machines.At at) {
        BlockPos machine = at.port().relative(at.side());
        if (!level.isLoaded(at.port()) || !level.isLoaded(machine)) {
            return Wait.UNLOADED;
        }
        if (Machines.port(level, network, at.port(), at.side()) == null) {
            return Wait.MISSING;
        }
        return level.getBlockEntity(machine) instanceof MachineBlockEntity grid && grid.opensToPorts() ? Wait.SIDE : Wait.MISSING;
    }

    /** A machine a set can go to right now. */
    private record Target(AccessPortBlockEntity port, Direction side) {
        Machines.At at() {
            return new Machines.At(port.getBlockPos(), side);
        }
    }

    /**
     * Sends sets of a processing card's ingredients into its machines, one set at a time, each to the machine with
     * the fewest sets out, while there are free Processor slots and ingredients.
     */
    static Sending send(ServerLevel level, @Nullable CableNetwork network, CraftingJob job, int stepIndex, ProcessingCard card,
            WaferRecord record, @Nullable WaferRecord fluids, WaferStore store, Map<ItemResource, Long> reserved, Map<ItemResource, Long> keep,
            int free) {
        CraftingJob.Step step = job.steps.get(stepIndex);
        List<Target> targets = new ArrayList<>();
        // the first machine that can't be used says why, should none of them take a set
        Wait problem = card.spots().isEmpty() ? Wait.MISSING : Wait.NONE;
        for (Machines.At at : card.spots()) {
            AccessPortBlockEntity port = Machines.reach(level, network, at);
            Wait wait = port == null ? unreachable(level, network, at)
                    : !port.running() || port.networkBlocked() || port.redstonePaused() ? Wait.PORT_OFF
                    : !port.freeFor(at.side(), job.id, stepIndex) ? Wait.IN_USE
                    : Wait.NONE;
            if (wait == Wait.NONE) {
                targets.add(new Target(port, at.side()));
            } else if (problem == Wait.NONE) {
                problem = wait;
            }
        }
        Map<FluidResource, Long> fluidSet = new HashMap<>();
        Map<MaterialKey, Long> materialSet = new HashMap<>();
        for (ProcessingCard.Amount input : card.usedInputs()) {
            if (input.isMaterial()) {
                materialSet.merge(input.material(), (long) input.count(), Long::sum);
            } else if (input.isFluid()) {
                fluidSet.merge(input.fluid(), (long) input.count(), Long::sum);
            }
        }
        List<ProcessingCard.Amount> expected = card.outputs().stream().filter(a -> !a.isEmpty()).toList();
        Map<Machines.At, Long> out = new HashMap<>();
        for (CraftingJob.Sent already : job.sent) {
            out.merge(already.at(), 1L, Long::sum);
        }
        NetworkPool pool = materialSet.isEmpty() || network == null ? null : NetworkPool.of(level, network);
        int sent = 0;
        Wait blocked = Wait.NONE;
        // what the machines that were tried said
        Wait tried = Wait.NONE;
        boolean refusedHere = false;
        while (sent < free && step.left > 0) {
            Choice choice = chooseSet(level, job, stepIndex, card, record, reserved, keep);
            boolean enough = choice.set() != null
                    && fluidSet.entrySet().stream().allMatch(e -> fluids != null && fluids.countFluid(e.getKey()) >= e.getValue());
            if (!enough) {
                if (choice.unchecked() && sent == 0) {
                    // The items planned in place of the card's own can't be checked against the machine now: wait for it.
                    blocked = problem != Wait.NONE ? problem : Wait.MISSING;
                } else if ((refusedHere || job.turnedAway(stepIndex)) && sent == 0) {
                    // The machine wouldn't take what is left: shown as any machine that refuses a set.
                    blocked = Wait.FULL;
                }
                break;
            }
            List<ProcessingCard.Amount> chosen = choice.set();
            Map<ItemResource, Long> set = new HashMap<>();
            for (ProcessingCard.Amount input : chosen) {
                if (!input.isFluid() && !input.isMaterial()) {
                    set.merge(input.item(), (long) input.count(), Long::sum);
                }
            }
            if (!materialSet.isEmpty() && (pool == null || !materialSet.entrySet().stream()
                    .allMatch(e -> pool.materialContents().getOrDefault(e.getKey(), 0L) >= e.getValue()))) {
                // nothing is held back for a job: it waits for the network's storage to hold the material
                blocked = sent > 0 ? Wait.NONE : Wait.NO_MATERIAL;
                break;
            }
            if (targets.isEmpty()) {
                blocked = sent > 0 ? Wait.NONE : problem.and(tried);
                break;
            }
            targets.sort(Comparator.comparingLong(t -> out.getOrDefault(t.at(), 0L)));
            Target target = targets.getFirst();
            Machines.Push pushed = Machines.push(target.port(), target.side(), chosen, pool);
            if (pushed == Machines.Push.SHORT) {
                blocked = sent > 0 ? Wait.NONE : Wait.NO_MATERIAL;
                break;
            }
            if (pushed == Machines.Push.REFUSED && turnedAway(job, stepIndex, card, chosen, target)) {
                // The machine never takes an item planned in place of the card's own: choose the set again without it.
                refusedHere = true;
                continue;
            }
            if (pushed != Machines.Push.SENT) {
                // Not now: try the others, and this one again next tick.
                tried = tried.and(switch (pushed) {
                    case WAIT -> Wait.UNTRIED;
                    case BUSY -> Wait.WORKING;
                    case SHUT -> Wait.SIDE;
                    default -> Wait.FULL;
                });
                targets.remove(target);
                continue;
            }
            set.forEach((key, n) -> {
                store.extract(record, key, n, false, null);
                step.use(key, n);
            });
            fluidSet.forEach((key, n) -> store.extractFluid(fluids, key, n, false, null));
            job.sent.add(new CraftingJob.Sent(stepIndex, target.port().getBlockPos(), target.side(), expected, level.getGameTime()));
            out.merge(target.at(), 1L, Long::sum);
            target.port().lock(target.side(), job.id, stepIndex);
            step.left--;
            sent++;
        }
        return new Sending(sent, blocked);
    }

    /**
     * After a machine refused a set: each item in it that stands in for the card's own and that the machine turns away
     * for good is marked refused for the rest of the job, so the next set is chosen without it. Returns whether any was.
     */
    private static boolean turnedAway(CraftingJob job, int stepIndex, ProcessingCard card, List<ProcessingCard.Amount> chosen, Target target) {
        List<ProcessingCard.Amount> own = card.usedInputs();
        boolean any = false;
        for (int i = 0; i < own.size(); i++) {
            ProcessingCard.Amount written = own.get(i);
            ProcessingCard.Amount sent = chosen.get(i);
            if (written.isFluid() || written.isMaterial() || sent.item().equals(written.item())) {
                continue;
            }
            if (Machines.turnsAway(target.port(), target.side(), written, sent)) {
                job.refuse(stepIndex, i, sent.item());
                any = true;
            }
        }
        return any;
    }

    /**
     * One set's items, or null when the job holds too little; {@code unchecked} when planned items could have filled
     * it but the machines can't be looked at now to make sure they take them.
     */
    private record Choice(@Nullable List<ProcessingCard.Amount> set, boolean unchecked) {}

    /**
     * The items for one set of a processing card from what the job holds. Each item slot takes the card's own item
     * while the step's share of it lasts, then another item from the share that the machines' recipe takes there (and
     * no machine has turned away), then the card's own item again. A slot gets one kind of item per set. Fluids and
     * materials are as written, and a job planned before shares were kept takes exactly what the card says.
     */
    static Choice chooseSet(ServerLevel level, CraftingJob job, int stepIndex, ProcessingCard card, WaferRecord record,
            Map<ItemResource, Long> reserved, Map<ItemResource, Long> keep) {
        CraftingJob.Step step = job.steps.get(stepIndex);
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        List<ProcessingCard.Amount> chosen = new ArrayList<>(inputs.size());
        Map<ItemResource, Long> using = new HashMap<>();
        boolean unchecked = false;
        // the card's own items: what the plan gave besides them can only be stand-ins in the slots the recipe opens
        Set<ItemResource> written = new HashSet<>();
        inputs.forEach(input -> written.add(input.item()));
        for (int i = 0; i < inputs.size(); i++) {
            ProcessingCard.Amount input = inputs.get(i);
            if (input.isFluid() || input.isMaterial()) {
                chosen.add(input);
                continue;
            }
            long n = input.count();
            ItemResource own = input.item();
            ItemResource pick = own;
            if (step.uses != null && (left(step, using, own) < n || free(record, reserved, keep, using,own) < n)) {
                List<ItemResource> others = new ArrayList<>();
                for (ItemResource key : step.uses.keySet()) {
                    if (!written.contains(key) && left(step, using, key) >= n && free(record, reserved, keep, using,key) >= n) {
                        others.add(key);
                    }
                }
                if (!others.isEmpty()) {
                    MachineRecipes.Match recipe = job.machineRecipe(level, stepIndex);
                    if (recipe == null) {
                        unchecked = true;
                    } else if (recipe.opens(i)) {
                        int slot = i;
                        pick = others.stream().filter(key -> job.takes(level, stepIndex, recipe, slot, key)).findFirst().orElse(own);
                    }
                }
            }
            if (free(record, reserved, keep, using,pick) < n) {
                return new Choice(null, unchecked);
            }
            using.merge(pick, n, Long::sum);
            chosen.add(pick.equals(own) ? input : ProcessingCard.Amount.of(pick, (int) n));
        }
        return new Choice(chosen, false);
    }

    /** What is left of the step's share of {@code key} once this set's other slots have had theirs. */
    private static long left(CraftingJob.Step step, Map<ItemResource, Long> using, ItemResource key) {
        return step.share(key) - using.getOrDefault(key, 0L);
    }

    /**
     * How many of {@code key} the job holds that no running craft and no other slot of this set has claimed, past what
     * the step must leave alone ({@code keep}: the seeds of other steps that copy an item from itself).
     */
    private static long free(WaferRecord record, Map<ItemResource, Long> reserved, Map<ItemResource, Long> keep, Map<ItemResource, Long> using,
            ItemResource key) {
        return record.count(key) - reserved.getOrDefault(key, 0L) - keep.getOrDefault(key, 0L) - using.getOrDefault(key, 0L);
    }

    /**
     * The seed each step that copies an item from itself needs for its next craft, while it has crafts left. Other steps
     * leave those copies alone, so the copying never runs out of copies to start from.
     */
    static Map<ItemResource, Long> seeds(ServerLevel level, CraftingJob job) {
        Map<ItemResource, Long> seeds = new HashMap<>();
        for (int i = 0; i < job.steps.size(); i++) {
            CraftingJob.Growing growing = job.steps.get(i).left > 0 ? job.growing(level, i) : null;
            if (growing != null) {
                seeds.merge(growing.item(), growing.perCraft(), Long::sum);
            }
        }
        return seeds;
    }

    /** What step {@code index} must leave alone: every seed but the one it grows from itself. */
    static Map<ItemResource, Long> keepFor(ServerLevel level, CraftingJob job, int index, Map<ItemResource, Long> seeds) {
        CraftingJob.Growing own = seeds.isEmpty() ? null : job.growing(level, index);
        if (own == null || !seeds.containsKey(own.item())) {
            return seeds;
        }
        Map<ItemResource, Long> keep = new HashMap<>(seeds);
        keep.remove(own.item());
        return keep;
    }

    /**
     * Takes in what came back through the ports the job's sets went to, counting it toward the oldest sets first. A
     * port can serve several jobs through its different machines: each job takes what its own sets wait for, and
     * anything else only when it is the one job using the port. A set with nothing left to wait for frees its
     * Processor slot, and a machine with no sets left is free again. Returns whether anything arrived.
     */
    static boolean collect(ServerLevel level, @Nullable CableNetwork network, CraftingJob job, WaferRecord record,
            @Nullable WaferRecord fluids, WaferStore store) {
        if (job.sent.isEmpty()) {
            return false;
        }
        boolean any = false;
        // a port only has allowance now and then, so the "no room" verdict stands until the next time one has
        boolean checked = false;
        boolean[] noRoom = {false};
        Map<AccessPortBlockEntity, List<CraftingJob.Sent>> ports = new LinkedHashMap<>();
        for (CraftingJob.Sent set : job.sent) {
            AccessPortBlockEntity port = Machines.port(level, network, set.port, set.side);
            if (port != null) ports.computeIfAbsent(port, unused -> new ArrayList<>()).add(set);
        }
        for (var entry : ports.entrySet()) {
            AccessPortBlockEntity port = entry.getKey();
            List<CraftingJob.Sent> here = entry.getValue();
            for (CraftingJob.Sent set : here) {
                if (port.lock(set.side) == null) {
                    // The port forgot (a crash rewound it, or it was mined and put back): the machine is this job's again.
                    port.lock(set.side, job.id, set.step);
                }
            }
            boolean alone = port.lockedJobs().equals(Set.of(job.id));
            if (port.transferBudget() <= 0) continue;
            for (Map.Entry<ItemResource, Long> held : port.intake().entrySet()) {
                ItemResource key = held.getKey();
                long wanted = here.stream().mapToLong(s -> s.wants(key)).sum();
                if (wanted == 0) continue;
                long taken = port.take(key, alone ? held.getValue() : Math.min(held.getValue(), wanted));
                if (taken <= 0) {
                    continue;
                }
                store.insert(record, key, taken, false, null);
                any = true;
                long left = taken;
                for (CraftingJob.Sent set : here) {
                    if (left <= 0) {
                        break;
                    }
                    left -= set.arrive(key, left);
                }
            }
            any |= pullFluids(port, here, fluids, store);
            checked = true;
            any |= pullMaterials(port, here, level, network, noRoom);
            job.sent.removeIf(CraftingJob.Sent::done);
            for (CraftingJob.Sent set : here) {
                AccessPortBlockEntity.Lock lock = port.lock(set.side);
                if (lock != null && lock.job().equals(job.id) && job.sent.stream().noneMatch(s -> s.at().equals(set.at()))) {
                    port.unlock(set.side);
                }
            }
        }
        if (checked) {
            job.noRoom = noRoom[0];
        }
        return any;
    }

    /**
     * Takes the fluids the sets on this port wait for out of their machines' tanks, as far as the port's allowance goes
     * (an eighth of a bucket for each share), and counts them toward the oldest sets first. Only what is waited for is
     * taken. Returns whether any came.
     */
    private static boolean pullFluids(AccessPortBlockEntity port, List<CraftingJob.Sent> here, @Nullable WaferRecord fluids, WaferStore store) {
        if (fluids == null) {
            return false;
        }
        boolean any = false;
        for (Direction side : here.stream().map(s -> s.side).distinct().toList()) {
            List<CraftingJob.Sent> onSide = here.stream().filter(s -> s.side == side).toList();
            Set<FluidResource> wanted = new LinkedHashSet<>();
            onSide.forEach(s -> s.waiting.stream().filter(ProcessingCard.Amount::isFluid).forEach(a -> wanted.add(a.fluid())));
            ResourceHandler<FluidResource> tank = wanted.isEmpty() ? null : Machines.fluidInlet(port, side);
            if (tank == null) {
                continue;
            }
            for (FluidResource fluid : wanted) {
                GridKey key = new GridKey.Fluid(fluid);
                long most = Math.min(onSide.stream().mapToLong(s -> s.wants(key)).sum(), (long) port.transferBudget() * FluidAmounts.PER_SHARE);
                most = Math.min(most, store.insertFluid(fluids, fluid, Math.min(most, Integer.MAX_VALUE), true, null));
                if (most <= 0) {
                    continue;
                }
                int got;
                try (Transaction tx = Transaction.openRoot()) {
                    got = tank.extract(fluid, (int) most, tx);
                    if (got > 0) {
                        tx.commit();
                    }
                }
                if (got <= 0) {
                    continue;
                }
                store.insertFluid(fluids, fluid, got, false, null);
                port.transferred((int) FluidAmounts.shares(got));
                any = true;
                long left = got;
                for (CraftingJob.Sent set : onSide) {
                    if (left <= 0) {
                        break;
                    }
                    left -= set.arrive(key, left);
                }
            }
        }
        return any;
    }

    /**
     * Takes the materials the sets on this port wait for out of their machines and into the network's storage, as far as
     * the port's allowance and the storage's room go. They never rest in the job. When the storage has no room, what
     * is waited for stays in the machine and the job says so. Returns whether any came.
     */
    private static boolean pullMaterials(AccessPortBlockEntity port, List<CraftingJob.Sent> here, ServerLevel level,
            @Nullable CableNetwork network, boolean[] noRoom) {
        boolean any = false;
        NetworkPool pool = null;
        for (Direction side : here.stream().map(s -> s.side).distinct().toList()) {
            List<CraftingJob.Sent> onSide = here.stream().filter(s -> s.side == side).toList();
            Set<MaterialKey> wanted = new LinkedHashSet<>();
            onSide.forEach(s -> s.waiting.stream().filter(ProcessingCard.Amount::isMaterial).forEach(a -> wanted.add(a.material())));
            if (wanted.isEmpty()) {
                continue;
            }
            List<Machines.MaterialInlet> vessels = Machines.materialInlets(port, side);
            if (vessels.isEmpty()) {
                continue;
            }
            if (pool == null && network != null) {
                pool = NetworkPool.of(level, network);
            }
            for (Machines.MaterialInlet vessel : vessels) {
                ResourceHandler<Resource> handler = vessel.handler();
                for (int slot = 0; slot < handler.size(); slot++) {
                    Material material = Material.of(vessel.kind(), handler.getResource(slot));
                    if (material == null || !wanted.contains(material.key()) || handler.getAmountAsLong(slot) <= 0) {
                        continue;
                    }
                    GridKey key = new GridKey.Material(material.key());
                    long most = Math.min(onSide.stream().mapToLong(s -> s.wants(key)).sum(), (long) port.transferBudget() * FluidAmounts.PER_SHARE);
                    if (most <= 0) {
                        continue;
                    }
                    long room = pool == null ? 0 : pool.roomMaterial(material, most, null);
                    if (room <= 0) {
                        noRoom[0] = true;
                        continue;
                    }
                    int offered;
                    try (Transaction tx = Transaction.openRoot()) {
                        offered = handler.extract(material.resource(), (int) Math.min(room, Integer.MAX_VALUE), tx);   // only asking
                    }
                    if (offered <= 0) {
                        continue;
                    }
                    long stored = pool.insertMaterialNow(material, offered, null);
                    if (stored <= 0) {
                        noRoom[0] = true;
                        continue;
                    }
                    int got;
                    try (Transaction tx = Transaction.openRoot()) {
                        got = handler.extract(material.resource(), (int) stored, tx);
                        if (got > 0) {
                            tx.commit();
                        }
                    }
                    if (got < stored) {
                        pool.extractMaterialNow(material, stored - got, null);
                    }
                    if (got <= 0) {
                        continue;
                    }
                    port.transferred((int) FluidAmounts.shares(got));
                    any = true;
                    long left = got;
                    for (CraftingJob.Sent set : onSide) {
                        if (left <= 0) {
                            break;
                        }
                        left -= set.arrive(key, left);
                    }
                }
            }
        }
        return any;
    }

    /** The job stops waiting on machines: its sets are forgotten and its machines let go. */
    static void release(ServerLevel level, CraftingJob job) {
        for (CraftingJob.Sent set : job.sent) {
            if (level.isLoaded(set.port)
                    && Machines.port(level, Networks.at(level, set.port), set.port, set.side) instanceof AccessPortBlockEntity port) {
                port.unlockJob(job.id);
            }
        }
        job.sent.clear();
    }

    /** The machine the job has waited on longest: its name, what is still to come, and for how long. */
    static CraftingJob.@Nullable Waiting waiting(ServerLevel level, @Nullable CableNetwork network, CraftingJob job) {
        CraftingJob.Sent oldest = job.sent.stream().min(Comparator.comparingLong(s -> s.since)).orElse(null);
        if (oldest == null || oldest.waiting.isEmpty()) {
            return null;
        }
        GridKey what = oldest.waiting.getFirst().key();
        long count = 0;
        for (CraftingJob.Sent set : job.sent) {
            if (set.at().equals(oldest.at())) {
                count += set.wants(what);
            }
        }
        AccessPortBlockEntity port = Machines.port(level, network, oldest.port, oldest.side);
        String name = port == null
                ? Machines.blockName(level, oldest.port.relative(oldest.side)).getString()
                : port.machineName(oldest.side).getString();
        return new CraftingJob.Waiting(name, what, count, Math.max(0, level.getGameTime() - oldest.since));
    }

    /** Ingredients running crafts have set aside. */
    static Map<ItemResource, Long> reserved(CraftingJob job) {
        Map<ItemResource, Long> reserved = new HashMap<>();
        for (CraftingJob.Running craft : job.running) {
            for (ItemResource key : craft.inputs) {
                if (!key.isEmpty()) {
                    reserved.merge(key, 1L, Long::sum);
                }
            }
        }
        return reserved;
    }

    /**
     * One item for each slot of the card from what the job holds; null if a slot has nothing. A slot takes from the
     * step's share first: the encoded item, then whichever other planned item it accepts there is most of. Past the
     * share (and in a job planned before shares were kept) it takes whatever it accepts there is most of.
     */
    static @Nullable List<ItemResource> chooseInputs(ServerLevel level, CraftingJob job, int step, CardRecipes.Resolved card,
            WaferRecord record, Map<ItemResource, Long> reserved, Map<ItemResource, Long> keep) {
        CraftingJob.Step planned = job.steps.get(step);
        List<ItemResource> inputs = new ArrayList<>();
        Map<ItemResource, Long> using = new HashMap<>();
        for (int slot = 0; slot < 9; slot++) {
            if (card.encoded(slot).isEmpty()) {
                inputs.add(ItemResource.EMPTY);
                continue;
            }
            ItemResource best = null;
            if (planned.uses != null) {
                ItemResource own = ItemResource.of(card.encoded(slot));
                if (left(planned, using, own) > 0 && free(record, reserved, keep, using,own) > 0) {
                    best = own;
                } else {
                    long bestFree = 0;
                    for (ItemResource key : planned.uses.keySet()) {
                        long free = free(record, reserved, keep, using,key);
                        if (left(planned, using, key) > 0 && free > bestFree && job.accepts(level, step, card, slot, key)) {
                            best = key;
                            bestFree = free;
                        }
                    }
                }
            }
            if (best == null) {
                long bestFree = 0;
                for (Map.Entry<ItemResource, Long> held : record.contents().entrySet()) {
                    long free = free(record, reserved, keep, using, held.getKey());
                    if (free > bestFree && job.accepts(level, step, card, slot, held.getKey())) {
                        best = held.getKey();
                        bestFree = free;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            using.merge(best, 1L, Long::sum);
            inputs.add(best);
        }
        return inputs;
    }

    /** The requester stops future crafts; running ones finish and everything left goes back. */
    public static boolean cancel(ServerPlayer player, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        if (job == null || job.phase != CraftingJob.Phase.CRAFTING || !job.requester.equals(player.getUUID())) {
            return false;
        }
        job.phase = CraftingJob.Phase.CANCELLING;
        server.setChanged();
        return true;
    }

    /** Cancels the job on {@code pos} if it belongs to this Deck (the Deck's Craft tab). */
    public static boolean cancel(ServerPlayer player, ItemStack deck, BlockPos pos) {
        EncodingTerminalBlockEntity terminal = JobPlanning.terminalOf(player.level().getServer(), deck);
        if (terminal == null || !(terminal.getLevel().getBlockEntity(pos) instanceof CraftingServerBlockEntity server)
                || server.job() == null || !server.job().requester.equals(player.getUUID())) {
            return false;
        }
        return cancel(player, server);
    }

    static @Nullable WaferRecord record(WaferStore store, CraftingJob job) {
        return store.jobRecord(job.serial, job.recordId).orElse(null);
    }

    /** The job's record of fluids; null when it has none (or it can't be read). */
    static @Nullable WaferRecord fluidRecord(WaferStore store, CraftingJob job) {
        return job.fluidRecordId == null ? null : store.jobRecord(job.fluidSerial, job.fluidRecordId).orElse(null);
    }
}
