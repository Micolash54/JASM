package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
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
        PauseReason machineBlocked = PauseReason.NONE;
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack requesterDeck = requester == null ? ItemStack.EMPTY : JobReturns.findDeck(requester, job.deck);
        boolean dimensionBlocked = !requesterDeck.isEmpty()
                && (!DeckItem.worksIn(requesterDeck, requester.level()) || !DeckItem.worksIn(requesterDeck, level));
        if (job.phase == CraftingJob.Phase.CRAFTING && dimensionBlocked) machineBlocked = PauseReason.DIMENSION_UPGRADE;
        if (job.phase == CraftingJob.Phase.CRAFTING && !dimensionBlocked) {
            int free = server.parallel() - job.running.size() - job.sent.size();
            Map<ItemResource, Long> reserved = reserved(job);
            for (int i = 0; i < job.steps.size() && free > 0; i++) {
                CraftingJob.Step step = job.steps.get(i);
                if (step.left <= 0) {
                    continue;
                }
                if (!cards.contains(step.card)) {
                    missingCard = true;
                    continue;
                }
                if (step.card instanceof ProcessingCard processing) {
                    Sending sending = send(level, network, job, i, processing, record, fluids, store, reserved, free);
                    free -= sending.sent();
                    started |= sending.sent() > 0;
                    if (machineBlocked == PauseReason.NONE) {
                        machineBlocked = sending.blocked();
                    }
                    continue;
                }
                CardRecipes.Resolved card = job.resolved(level, i);
                while (free > 0 && step.left > 0 && card != null) {
                    List<ItemResource> inputs = chooseInputs(level, job, i, card, record, reserved);
                    if (inputs == null) {
                        break;
                    }
                    inputs.forEach(key -> {
                        if (!key.isEmpty()) {
                            reserved.merge(key, 1L, Long::sum);
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
        job.pause = job.noRoom ? PauseReason.NO_ROOM : !idle ? PauseReason.NONE : missingCard ? PauseReason.NO_CARD : machineBlocked;
        job.waiting = waiting(level, network, job);
        boolean stepsLeft = job.steps.stream().anyMatch(s -> s.left > 0);
        if (machineBlocked != PauseReason.NONE) {
            job.stuck = 0;
        }
        if (idle) {
            if (job.phase == CraftingJob.Phase.CANCELLING || !stepsLeft) {
                if (job.phase == CraftingJob.Phase.CRAFTING && requester != null) JasmTriggers.AUTOCRAFT_DONE.get().trigger(requester);
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = PauseReason.NONE;
                server.setChanged();
            } else if (!missingCard && machineBlocked == PauseReason.NONE && !started && ++job.stuck >= STUCK_TICKS) {
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
                job.steps.get(craft.step).left++;
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

    /**
     * How sending went: how many sets went, and, when the ingredients were there but nothing could go, why:
     * {@link PauseReason#NO_MACHINE} (none of the card's machines can be reached) or {@link PauseReason#MACHINE_BUSY}
     * (they are taken by another card, full, or their port has no power). {@link PauseReason#NONE} otherwise.
     */
    private record Sending(int sent, PauseReason blocked) {}

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
            WaferRecord record, @Nullable WaferRecord fluids, WaferStore store, Map<ItemResource, Long> reserved, int free) {
        CraftingJob.Step step = job.steps.get(stepIndex);
        List<Target> targets = new ArrayList<>();
        boolean anyMachine = false;
        for (Machines.At at : card.spots()) {
            AccessPortBlockEntity port = Machines.reach(level, network, at);
            if (port != null) {
                anyMachine = true;
                if (port.running() && port.freeFor(at.side(), job.id, stepIndex)) {
                    targets.add(new Target(port, at.side()));
                }
            }
        }
        Map<ItemResource, Long> set = new HashMap<>();
        Map<FluidResource, Long> fluidSet = new HashMap<>();
        Map<MaterialKey, Long> materialSet = new HashMap<>();
        for (ProcessingCard.Amount input : card.usedInputs()) {
            if (input.isMaterial()) {
                materialSet.merge(input.material(), (long) input.count(), Long::sum);
            } else if (input.isFluid()) {
                fluidSet.merge(input.fluid(), (long) input.count(), Long::sum);
            } else {
                set.merge(input.item(), (long) input.count(), Long::sum);
            }
        }
        List<ProcessingCard.Amount> expected = card.outputs().stream().filter(a -> !a.isEmpty()).toList();
        Map<Machines.At, Long> out = new HashMap<>();
        for (CraftingJob.Sent already : job.sent) {
            out.merge(already.at(), 1L, Long::sum);
        }
        NetworkPool pool = materialSet.isEmpty() || network == null ? null : NetworkPool.of(level, network);
        int sent = 0;
        PauseReason blocked = PauseReason.NONE;
        while (sent < free && step.left > 0) {
            boolean enough = set.entrySet().stream()
                    .allMatch(e -> record.count(e.getKey()) - reserved.getOrDefault(e.getKey(), 0L) >= e.getValue())
                    && fluidSet.entrySet().stream().allMatch(e -> fluids != null && fluids.countFluid(e.getKey()) >= e.getValue());
            if (!enough) {
                break;
            }
            if (!materialSet.isEmpty() && (pool == null || !materialSet.entrySet().stream()
                    .allMatch(e -> pool.materialContents().getOrDefault(e.getKey(), 0L) >= e.getValue()))) {
                // nothing is held back for a job: it waits for the network's storage to hold the material
                blocked = sent > 0 ? PauseReason.NONE : PauseReason.NO_MATERIAL;
                break;
            }
            if (targets.isEmpty()) {
                blocked = sent > 0 ? PauseReason.NONE : anyMachine ? PauseReason.MACHINE_BUSY : PauseReason.NO_MACHINE;
                break;
            }
            targets.sort(Comparator.comparingLong(t -> out.getOrDefault(t.at(), 0L)));
            Target target = targets.getFirst();
            Machines.Push pushed = Machines.push(target.port(), target.side(), card.usedInputs(), pool);
            if (pushed == Machines.Push.SHORT) {
                blocked = sent > 0 ? PauseReason.NONE : PauseReason.NO_MATERIAL;
                break;
            }
            if (pushed != Machines.Push.SENT) {
                // Full, or it doesn't take these: try the others, and this one again next tick.
                targets.remove(target);
                continue;
            }
            set.forEach((key, n) -> store.extract(record, key, n, false, null));
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

    /** One item for each slot of the card from what the job holds, the most plentiful first; null if a slot has nothing. */
    static @Nullable List<ItemResource> chooseInputs(ServerLevel level, CraftingJob job, int step, CardRecipes.Resolved card,
            WaferRecord record, Map<ItemResource, Long> reserved) {
        List<ItemResource> inputs = new ArrayList<>();
        Map<ItemResource, Long> using = new HashMap<>();
        for (int slot = 0; slot < 9; slot++) {
            if (card.encoded(slot).isEmpty()) {
                inputs.add(ItemResource.EMPTY);
                continue;
            }
            ItemResource best = null;
            long bestFree = 0;
            for (Map.Entry<ItemResource, Long> held : record.contents().entrySet()) {
                long free = held.getValue() - reserved.getOrDefault(held.getKey(), 0L) - using.getOrDefault(held.getKey(), 0L);
                if (free > bestFree && job.accepts(level, step, card, slot, held.getKey())) {
                    best = held.getKey();
                    bestFree = free;
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
