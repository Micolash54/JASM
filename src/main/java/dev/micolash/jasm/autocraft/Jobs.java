package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.CraftPlanner;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckViewTracker;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Everything about crafting jobs: planning a request, starting it, running its crafts, and handing the results back.
 *
 * <p>A job's items live in a hidden record of the wafer store. Taking ingredients from the requester's wafers and
 * putting results back change both records with the requester as the one who changed them, so both are written after
 * the requester's file, the same as any Deck operation. A craft takes its ingredients and adds its result in one step
 * when it finishes, so a crash can rewind a job, but never lose or copy what it held.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Jobs {
    /** Ticks between attempts to hand results back. */
    private static final int DELIVER_EVERY = 20;
    /** Ticks with nothing running and nothing able to start before a job gives up and returns what it has. */
    private static final int STUCK_TICKS = 100;

    private Jobs() {}

    // --- finding the network ---

    /** The network a paired Crafting Deck belongs to, if its terminal stands in a loaded spot and has power. */
    public static @Nullable EncodingTerminalBlockEntity terminalOf(MinecraftServer server, ItemStack deck) {
        UUID id = deck.get(JasmComponents.DECK_NETWORK.get());
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (id == null || deckId == null) {
            return null;
        }
        AutocraftState state = AutocraftState.get(server);
        if (!state.isActive(id, deckId)) {
            return null;
        }
        Optional<AutocraftState.Terminal> entry = state.terminal(id);
        if (entry.isEmpty()) {
            return null;
        }
        ServerLevel level = server.getLevel(entry.get().placement().dimension());
        BlockPos pos = entry.get().placement().pos();
        if (level == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof EncodingTerminalBlockEntity terminal)
                || !id.equals(terminal.terminalId())) {
            return null;
        }
        return terminal;
    }

    /** Whether the block at {@code pos} is on the network this Crafting Deck is paired with. */
    public static boolean onDeckNetwork(ItemStack deck, ServerLevel level, BlockPos pos) {
        EncodingTerminalBlockEntity terminal = terminalOf(level.getServer(), deck);
        if (terminal == null || terminal.getLevel() != level) {
            return false;
        }
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        return network != null && network.contains(pos);
    }

    /** Cards on {@code network} in racks with power that {@code player} may use. */
    public static List<Card> cards(CableNetwork network, ServerPlayer player) {
        Set<Card> cards = new LinkedHashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            if (MachineAccess.canUse(rack, player)) {
                cards.addAll(rack.cards());
            }
        }
        return List.copyOf(cards);
    }

    /** Every card on the network, whoever may use them: what a running job may keep using. */
    static Set<Card> allCards(CableNetwork network) {
        Set<Card> cards = new HashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            cards.addAll(rack.cards());
        }
        return cards;
    }

    /** Whether a processing card has a machine it can use: one of its ports stands on the network and faces something. */
    static boolean reachable(ServerLevel level, @Nullable CableNetwork network, ProcessingCard card) {
        for (Machines.At at : card.spots()) {
            if (Machines.reach(level, network, at) != null) {
                return true;
            }
        }
        return false;
    }

    // --- planning ---

    /** A server a request could go to. */
    public record ServerOption(BlockPos pos, int memory, int parallel, boolean busy, boolean fits) {}

    /** A planned request: the plan, the servers, and which one it would go to (-1 when none can take it). */
    public record Preview(CraftPlanner.Plan<ItemResource> plan, List<ServerOption> servers, int chosen, @Nullable String problem) {}

    /** Plans {@code amount} of {@code target} for {@code player}'s Crafting Deck, without changing anything. */
    public static Preview preview(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted) {
        CraftPlanner.Plan<ItemResource> empty = new CraftPlanner.Plan<>(CraftPlanner.Problem.NO_PATTERN, Map.of(), Map.of(), List.of(), 0, 0);
        if (!DeckItem.isCrafting(deck)) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.not_crafting_deck");
        }
        if (!DeckItem.worksIn(deck, player.level())) return new Preview(empty, List.of(), -1, "message.jasm.deck.dimension_upgrade");
        EncodingTerminalBlockEntity terminal = terminalOf(player.level().getServer(), deck);
        if (terminal == null) {
            return new Preview(empty, List.of(), -1, deck.has(JasmComponents.DECK_NETWORK.get())
                    ? "message.jasm.craft.network_unreachable" : "message.jasm.craft.not_paired");
        }
        if (!AutocraftState.get(player.level().getServer()).isPaired(terminal.terminalId(), player.getUUID(),
                deck.get(JasmComponents.DECK_ID.get()))) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.no_access");
        }
        if (!MachineAccess.canUse(terminal, player)) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.no_access");
        }
        if (!terminal.running()) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.network_unreachable");
        }
        ServerLevel level = (ServerLevel) terminal.getLevel();
        if (!DeckItem.worksIn(deck, level)) return new Preview(empty, List.of(), -1, "message.jasm.deck.dimension_upgrade");
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        if (network == null) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.network_unreachable");
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        DeckStorage.checkAll(store, deck, player);
        Map<ItemResource, Long> stock = DeckStorage.contents(store, deck);
        CardBook book = new CardBook(level, cards(network, player), stock.keySet(), card -> reachable(level, network, card));
        CraftPlanner.Plan<ItemResource> plan = CraftPlanner.plan(target, Math.max(1, amount), stock, book);
        List<ServerOption> servers = new ArrayList<>();
        for (CraftingServerBlockEntity server : network.machines(CraftingServerBlockEntity.class)) {
            if (MachineAccess.canUse(server, player) && server.parallel() > 0) {
                servers.add(new ServerOption(server.getBlockPos(), server.memory(), server.parallel(), server.busy(), server.memory() >= plan.size()));
            }
        }
        servers.sort(Comparator.comparingInt(ServerOption::memory).thenComparing(o -> o.pos().asLong()));
        int chosen = -1;
        for (int i = 0; i < servers.size(); i++) {
            ServerOption option = servers.get(i);
            if (!option.busy() && option.fits() && (chosen < 0 || option.pos().equals(wanted))) {
                chosen = i;
            }
        }
        String problem = switch (plan.problem()) {
            case NO_PATTERN -> book.unreachable(target) ? "message.jasm.craft.machine_missing" : "message.jasm.craft.no_card";
            case MISSING -> plan.missing().keySet().stream().anyMatch(book::unreachable) ? "message.jasm.craft.machine_missing"
                    : "message.jasm.craft.missing";
            case TOO_COMPLEX -> "message.jasm.craft.too_complex";
            case NONE -> servers.isEmpty() ? "message.jasm.craft.no_server"
                    : chosen < 0 ? (servers.stream().anyMatch(ServerOption::fits) ? "message.jasm.craft.servers_busy" : "message.jasm.craft.too_big")
                    : null;
        };
        return new Preview(plan, servers, chosen, problem);
    }

    /**
     * Starts a request: plans it again (the screen is never trusted), takes every ingredient from the Deck's wafers
     * at once, and gives the job to the chosen server. Returns null when it started, or the message saying why not.
     */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted) {
        return start(player, deck, target, amount, wanted, null);
    }

    /** As {@link #start(ServerPlayer, ItemStack, ItemResource, long, BlockPos)}, for a rule of the Deck ({@code rule} is its id). */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted,
            @Nullable UUID rule) {
        return start(player, deck, target, amount, wanted, rule, false);
    }

    /** As above; {@code toPlayer}: the results go into the player's inventory instead of onto the Deck. */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted,
            @Nullable UUID rule, boolean toPlayer) {
        Preview preview = preview(player, deck, target, amount, wanted);
        if (preview.problem() != null) {
            return preview.problem();
        }
        if (!DeckStorage.hasPower(deck)) {
            return "message.jasm.deck.no_power";
        }
        ServerOption option = preview.servers().get(preview.chosen());
        EncodingTerminalBlockEntity terminal = terminalOf(player.level().getServer(), deck);
        ServerLevel level = (ServerLevel) terminal.getLevel();
        if (!(level.getBlockEntity(option.pos()) instanceof CraftingServerBlockEntity server) || server.busy()) {
            return "message.jasm.craft.servers_busy";
        }
        WaferStore store = WaferStore.get(level.getServer());
        List<WaferRecord> wafers = DeckStorage.records(store, deck);
        // Take everything, or nothing.
        List<Taken> taken = new ArrayList<>();
        for (Map.Entry<ItemResource, Long> need : preview.plan().taken().entrySet()) {
            long left = need.getValue();
            for (WaferRecord wafer : wafers) {
                if (wafer != null && left > 0) {
                    long got = store.extract(wafer, need.getKey(), left, false, player);
                    if (got > 0) {
                        taken.add(new Taken(wafer, need.getKey(), got));
                        left -= got;
                    }
                }
            }
            if (left > 0) {
                taken.forEach(t -> store.insert(t.wafer(), t.key(), t.amount(), false, player));
                return "message.jasm.craft.missing";
            }
        }
        WaferRecord record = store.create(Integer.MAX_VALUE, player);
        for (Taken t : taken) {
            store.insert(record, t.key(), t.amount(), false, player);
        }
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (deckId == null) {
            deckId = UUID.randomUUID();
            deck.set(JasmComponents.DECK_ID.get(), deckId);
        }
        List<CraftingJob.Step> steps = new ArrayList<>();
        for (CraftPlanner.Step<ItemResource> step : preview.plan().steps()) {
            steps.add(CraftingJob.step(((CardBook.Entry) step.pattern()).card(), step.crafts()));
        }
        UUID id = UUID.randomUUID();
        CraftingJob job = CraftingJob.start(id, record.serial(), record.id(), player.getUUID(), player.getPlainTextName(), deckId,
                ItemStackTemplate.fromNonEmptyStack(target.toStack(1)),
                preview.plan().made(), steps, toPlayer);
        server.setJob(job);
        AutocraftState state = AutocraftState.get(level.getServer());
        state.addJob(new AutocraftState.Job(id, record.serial(), record.id(), new ArchiveRecord.Placement(level.dimension(), server.getBlockPos()),
                player.getUUID(), player.getPlainTextName(), Optional.of(deckId), false, Optional.ofNullable(rule)));
        state.saveNow(level.getServer());
        refreshOpenDeck(player, deck);
        return null;
    }

    private record Taken(WaferRecord wafer, ItemResource key, long amount) {}

    // --- running ---

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
            finish(level, server, job);
            return;
        }
        if (job.phase == CraftingJob.Phase.RETURNING) {
            // The reason it waits is set by each delivery attempt and kept until the next, so screens see it steadily.
            if (level.getGameTime() % DELIVER_EVERY == 0) {
                deliver(level, server, job, record, store);
            }
            return;
        }
        if (!powered) {
            if (level.getGameTime() % DELIVER_EVERY == 0 && deliverTarget(level, server, job, record, store)) server.setChanged();
            job.pause = "no_power";
            return;
        }
        CableNetwork network = Networks.at(level, server.getBlockPos());
        Set<Card> cards = network == null ? Set.of() : allCards(network);
        boolean changed = finishCrafts(level, job, record, store);
        changed |= collect(level, network, job, record, store);
        if (job.phase == CraftingJob.Phase.CANCELLING && !job.sent.isEmpty()) {
            // Whatever is out in machines stays there; the job stops waiting for it.
            release(level, job);
            changed = true;
        }
        boolean started = false;
        boolean missingCard = false;
        // A machine that can't be used right now: the job waits for it rather than giving up.
        String machineBlocked = "";
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack requesterDeck = requester == null ? ItemStack.EMPTY : findDeck(requester, job.deck);
        boolean dimensionBlocked = !requesterDeck.isEmpty()
                && (!DeckItem.worksIn(requesterDeck, requester.level()) || !DeckItem.worksIn(requesterDeck, level));
        if (job.phase == CraftingJob.Phase.CRAFTING && dimensionBlocked) machineBlocked = "dimension_upgrade";
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
                    Sending sending = send(level, network, job, i, processing, record, store, reserved, free);
                    free -= sending.sent();
                    started |= sending.sent() > 0;
                    if (machineBlocked.isEmpty()) {
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
            changed |= deliverTarget(level, server, job, record, store);
        }
        if (started || changed) {
            server.setChanged();
        }
        boolean idle = job.running.isEmpty() && job.sent.isEmpty();
        job.pause = !idle ? "" : missingCard ? "no_card" : machineBlocked;
        job.waiting = waiting(level, network, job);
        boolean stepsLeft = job.steps.stream().anyMatch(s -> s.left > 0);
        if (!machineBlocked.isEmpty()) {
            job.stuck = 0;
        }
        if (idle) {
            if (job.phase == CraftingJob.Phase.CANCELLING || !stepsLeft) {
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = "";
                server.setChanged();
            } else if (!missingCard && machineBlocked.isEmpty() && !started && ++job.stuck >= STUCK_TICKS) {
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = "";
                server.setChanged();
            }
        } else {
            job.stuck = 0;
        }
    }

    /** Crafts whose time is up: their ingredients go, their results come, in one step. Returns whether any finished. */
    private static boolean finishCrafts(ServerLevel level, CraftingJob job, WaferRecord record, WaferStore store) {
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
     * {@code "no_machine"} (none of the card's machines can be reached) or {@code "machine_busy"} (they are taken by
     * another card, full, or their port has no power). Empty otherwise.
     */
    private record Sending(int sent, String blocked) {}

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
    private static Sending send(ServerLevel level, @Nullable CableNetwork network, CraftingJob job, int stepIndex, ProcessingCard card,
            WaferRecord record, WaferStore store, Map<ItemResource, Long> reserved, int free) {
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
        for (ProcessingCard.Amount input : card.usedInputs()) {
            set.merge(input.item(), (long) input.count(), Long::sum);
        }
        List<ProcessingCard.Amount> expected = card.outputs().stream().filter(a -> !a.isEmpty()).toList();
        int sent = 0;
        String blocked = "";
        while (sent < free && step.left > 0) {
            boolean enough = set.entrySet().stream()
                    .allMatch(e -> record.count(e.getKey()) - reserved.getOrDefault(e.getKey(), 0L) >= e.getValue());
            if (!enough) {
                break;
            }
            if (targets.isEmpty()) {
                blocked = sent > 0 ? "" : anyMachine ? "machine_busy" : "no_machine";
                break;
            }
            targets.sort(Comparator.comparingLong(t -> job.sent.stream().filter(s -> s.at().equals(t.at())).count()));
            Target target = targets.getFirst();
            if (!Machines.push(target.port(), target.side(), card.usedInputs())) {
                // Full, or it doesn't take these: try the others, and this one again next tick.
                targets.remove(target);
                continue;
            }
            set.forEach((key, n) -> store.extract(record, key, n, false, null));
            job.sent.add(new CraftingJob.Sent(stepIndex, target.port().getBlockPos(), target.side(), expected, level.getGameTime()));
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
    private static boolean collect(ServerLevel level, @Nullable CableNetwork network, CraftingJob job, WaferRecord record, WaferStore store) {
        if (job.sent.isEmpty()) {
            return false;
        }
        boolean any = false;
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
            job.sent.removeIf(CraftingJob.Sent::done);
            for (CraftingJob.Sent set : here) {
                AccessPortBlockEntity.Lock lock = port.lock(set.side);
                if (lock != null && lock.job().equals(job.id) && job.sent.stream().noneMatch(s -> s.at().equals(set.at()))) {
                    port.unlock(set.side);
                }
            }
        }
        return any;
    }

    /** The job stops waiting on machines: its sets are forgotten and its machines let go. */
    private static void release(ServerLevel level, CraftingJob job) {
        for (CraftingJob.Sent set : job.sent) {
            if (level.isLoaded(set.port) && Machines.port(level, Networks.at(level, set.port), set.port, set.side) instanceof AccessPortBlockEntity port) {
                port.unlockJob(job.id);
            }
        }
        job.sent.clear();
    }

    /**
     * A chunk with a port or server of this job is being saved and unloaded: the job's items are written too, so a
     * crash later can't find a set both inside the machine and back in the job.
     */
    static void writeNow(MinecraftServer server, UUID jobId) {
        WaferStore store = WaferStore.ifOpen(server);
        AutocraftState.Job entry = AutocraftState.get(server).job(jobId).orElse(null);
        if (store != null && entry != null) {
            store.bySerial(entry.serial()).filter(r -> r.id().equals(entry.recordId())).ifPresent(store::writeNow);
        }
    }

    /** The machine the job has waited on longest: its name, what is still to come, and for how long. */
    private static CraftingJob.@Nullable Waiting waiting(ServerLevel level, @Nullable CableNetwork network, CraftingJob job) {
        CraftingJob.Sent oldest = job.sent.stream().min(Comparator.comparingLong(s -> s.since)).orElse(null);
        if (oldest == null || oldest.waiting.isEmpty()) {
            return null;
        }
        ItemResource item = oldest.waiting.getFirst().item();
        long count = 0;
        for (CraftingJob.Sent set : job.sent) {
            if (set.at().equals(oldest.at())) {
                count += set.wants(item);
            }
        }
        AccessPortBlockEntity port = Machines.port(level, network, oldest.port, oldest.side);
        String name = port == null ? Machines.blockName(level, oldest.port.relative(oldest.side)).getString()
                : port.machineName(oldest.side).getString();
        return new CraftingJob.Waiting(name, item, count, Math.max(0, level.getGameTime() - oldest.since));
    }

    /** Ingredients running crafts have set aside. */
    private static Map<ItemResource, Long> reserved(CraftingJob job) {
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
    private static @Nullable List<ItemResource> chooseInputs(ServerLevel level, CraftingJob job, int step, CardRecipes.Resolved card,
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

    // --- handing back ---

    /** Requested products can leave during a job; ingredients and other products stay until it ends. */
    private static boolean deliverTarget(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job, WaferRecord record, WaferStore store) {
        if (job.target == null) return false;
        ItemResource target = ItemResource.of(job.target.create());
        long available = record.count(target) - targetNeeded(level, job, target);
        if (available <= 0) return false;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack deck = player == null ? ItemStack.EMPTY : findDeck(player, job.deck);
        if (deck.isEmpty() || !DeckItem.worksIn(deck, player.level()) || !DeckItem.worksIn(deck, level)
                || !onDeckNetwork(deck, level, server.getBlockPos())) return false;
        Map<ItemResource, Long> products = Map.of(target, available);
        return (job.toPlayer ? moveToInventory(player, record, store, products) : moveToDeck(player, deck, record, store, products)) > 0;
    }

    /** Keep requested items that a running or remaining craft may still need as ingredients. */
    private static long targetNeeded(ServerLevel level, CraftingJob job, ItemResource target) {
        long needed = reserved(job).getOrDefault(target, 0L);
        if (job.phase != CraftingJob.Phase.CRAFTING) return needed;
        for (int stepIndex = 0; stepIndex < job.steps.size(); stepIndex++) {
            CraftingJob.Step step = job.steps.get(stepIndex);
            if (step.left <= 0) continue;
            long perCraft = 0;
            if (step.card instanceof ProcessingCard card) {
                perCraft = card.usedInputs().stream().filter(input -> input.item().equals(target)).mapToLong(ProcessingCard.Amount::count).sum();
            } else {
                CardRecipes.Resolved card = job.resolved(level, stepIndex);
                if (card == null) return Long.MAX_VALUE;
                for (int slot = 0; slot < 9; slot++) {
                    if (!card.encoded(slot).isEmpty() && job.accepts(level, stepIndex, card, slot, target)) perCraft++;
                }
            }
            if (perCraft > 0) {
                if (step.left > (Long.MAX_VALUE - needed) / perCraft) return Long.MAX_VALUE;
                needed += step.left * perCraft;
            }
        }
        return needed;
    }

    private static long moveToDeck(ServerPlayer player, ItemStack deck, WaferRecord record, WaferStore store, Map<ItemResource, Long> items) {
        prepareOpenDeck(player, deck);
        DeckStorage.checkAll(store, deck, player);
        long moved = 0;
        for (var held : DeckStorage.depositAmounts(store, deck, items, player).entrySet()) {
            moved += store.extract(record, held.getKey(), held.getValue(), false, player);
        }
        refreshOpenDeck(player, deck);
        return moved;
    }

    /** Puts what the job holds onto the requester's Crafting Deck, if they are online with it. */
    private static void deliver(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job, WaferRecord record, WaferStore store) {
        if (record.contents().isEmpty()) {
            finish(level, server, job);
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack deck = player == null ? ItemStack.EMPTY : findDeck(player, job.deck);
        if (deck.isEmpty()) {
            job.pause = "waiting_player";
            return;
        }
        if (!DeckItem.worksIn(deck, player.level()) || !DeckItem.worksIn(deck, level)) {
            job.pause = "dimension_upgrade";
            return;
        }
        if (!onDeckNetwork(deck, level, server.getBlockPos())) {
            // Results travel over the network: a server cut off from the Deck's network keeps them until it's back.
            job.pause = "no_network";
            return;
        }
        if (job.toPlayer) {
            moveToInventory(player, record, store);
            if (record.contents().isEmpty()) {
                finish(level, server, job);
            } else {
                job.pause = "waiting_space";
            }
            return;
        }
        moveToDeck(player, deck, record, store, new LinkedHashMap<>(record.contents()));
        if (record.contents().isEmpty()) {
            finish(level, server, job);
        } else {
            // The Deck's screen shows it: a banner, and the job in its list.
            job.pause = "waiting_space";
        }
    }

    /** The Crafting Deck with this identity in the player's inventory. */
    public static ItemStack findDeck(ServerPlayer player, @Nullable UUID deckId) {
        if (deckId == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (DeckItem.isCrafting(stack) && deckId.equals(stack.get(JasmComponents.DECK_ID.get()))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The job is over: the server is free, and its entry goes once its record is safely written. */
    private static void finish(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job) {
        release(level, job);
        server.setJob(null);
        AutocraftState.get(level.getServer()).finishJob(job.id);
    }

    // --- players ---

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
        EncodingTerminalBlockEntity terminal = terminalOf(player.level().getServer(), deck);
        if (terminal == null || !(terminal.getLevel().getBlockEntity(pos) instanceof CraftingServerBlockEntity server)
                || server.job() == null || !server.job().requester.equals(player.getUUID())) {
            return false;
        }
        return cancel(player, server);
    }

    /**
     * Takes a finished job's results into the player's inventory at the server, for when the Deck is lost. The
     * requester may collect. Returns how many items came out.
     */
    public static long collect(ServerPlayer player, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.get(player.level().getServer());
        if (job == null || job.phase != CraftingJob.Phase.RETURNING || !job.requester.equals(player.getUUID())) {
            return 0;
        }
        WaferRecord record = record(store, job);
        if (record == null) {
            return 0;
        }
        return moveToInventory(player, record, store);
    }

    /** Moves what the job holds into the player's inventory, as far as it fits. Returns how many items went in. */
    private static long moveToInventory(ServerPlayer player, WaferRecord record, WaferStore store) {
        return moveToInventory(player, record, store, new LinkedHashMap<>(record.contents()));
    }

    private static long moveToInventory(ServerPlayer player, WaferRecord record, WaferStore store, Map<ItemResource, Long> items) {
        long moved = 0;
        Inventory inventory = player.getInventory();
        for (Map.Entry<ItemResource, Long> held : items.entrySet()) {
            ItemResource key = held.getKey();
            long left = held.getValue();
            while (left > 0) {
                int size = (int) Math.min(left, key.getMaxStackSize());
                ItemStack stack = key.toStack(size);
                if (!inventory.add(stack)) {
                    // The inventory is full; whatever did go in is taken off the job.
                    int in = size - stack.getCount();
                    if (in > 0) {
                        store.extract(record, key, in, false, player);
                        moved += in;
                    }
                    return moved;
                }
                store.extract(record, key, size, false, player);
                moved += size;
                left -= size;
            }
        }
        return moved;
    }

    /** The server is being broken: its job ends and everything it held spills on the ground. */
    static void spill(ServerLevel level, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.ifOpen(level.getServer());
        if (job == null || store == null) {
            return;
        }
        WaferRecord record = record(store, job);
        if (record != null) {
            BlockPos pos = server.getBlockPos();
            for (Map.Entry<ItemResource, Long> held : List.copyOf(record.contents().entrySet())) {
                long left = held.getValue();
                while (left > 0) {
                    int size = (int) Math.min(left, held.getKey().getMaxStackSize());
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), held.getKey().toStack(size));
                    left -= size;
                }
                store.extract(record, held.getKey(), held.getValue(), false, null);
            }
        }
        finish(level, server, job);
    }

    // --- after a crash ---

    /** A job the list says belongs here, but the block lost (its chunk was saved before the job started): it returns its items. */
    public static void adopt(ServerLevel level, CraftingServerBlockEntity server) {
        if (server.busy()) {
            return;
        }
        ArchiveRecord.Placement here = new ArchiveRecord.Placement(level.dimension(), server.getBlockPos());
        WaferStore store = WaferStore.ifOpen(level.getServer());
        if (store == null) {
            return;
        }
        for (AutocraftState.Job entry : AutocraftState.get(level.getServer()).jobs()) {
            if (!entry.server().equals(here)) {
                continue;
            }
            WaferRecord record = store.bySerial(entry.serial()).filter(r -> r.id().equals(entry.recordId())).orElse(null);
            if (record != null && !record.contents().isEmpty()) {
                Jasm.LOGGER.warn("Crafting Server at {} lost its job {} in a crash; returning its items", here, entry.id());
                server.setJob(CraftingJob.adopted(entry));
                return;
            }
        }
    }

    /** Finished jobs leave the list once their records are on disk. */
    @SubscribeEvent
    static void cleanUp(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        WaferStore store = WaferStore.ifOpen(server);
        if (store == null) {
            return;
        }
        AutocraftState state = AutocraftState.get(server);
        for (AutocraftState.Job entry : state.jobs()) {
            if (entry.finished()) {
                WaferRecord record = store.bySerial(entry.serial()).orElse(null);
                if (record == null || !record.isDirty()) {
                    state.removeJob(entry.id());
                }
            }
        }
    }

    // --- helpers ---

    private static @Nullable WaferRecord record(WaferStore store, CraftingJob job) {
        return store.bySerial(job.serial).filter(r -> r.id().equals(job.recordId)).orElse(null);
    }

    /** An open Deck screen keeps working copies of the wafers; bring them up to date before and after a change. */
    public static void prepareOpenDeck(ServerPlayer player, ItemStack deck) {
        if (player.containerMenu instanceof DeckMenu menu && menu.deck() == deck) {
            menu.wafers().flush();
        }
    }

    public static void refreshOpenDeck(ServerPlayer player, ItemStack deck) {
        if (player.containerMenu instanceof DeckMenu menu && menu.deck() == deck) {
            menu.wafers().reload();
            DeckViewTracker.markDirty(menu);
        }
    }

    /** Null means a locked job is unloaded: keep its possible returns until it can be checked. */
    static @Nullable Set<ItemResource> expectedPortReturns(ServerLevel level, AccessPortBlockEntity port) {
        Set<ItemResource> expected = new HashSet<>();
        var state = AutocraftState.get(level.getServer());
        for (UUID id : port.lockedJobs()) {
            var entry = state.job(id).orElse(null);
            if (entry == null || entry.finished()) continue;
            ServerLevel jobLevel = level.getServer().getLevel(entry.server().dimension());
            BlockPos pos = entry.server().pos();
            if (jobLevel == null || !jobLevel.isLoaded(pos) || !(jobLevel.getBlockEntity(pos) instanceof CraftingServerBlockEntity server)
                    || server.job() == null || !server.job().id().equals(id)) return null;
            for (var sent : server.job().sent) {
                if (sent.port.equals(port.getBlockPos()) && port.lock(sent.side) != null) {
                    sent.waiting.forEach(amount -> expected.add(amount.item()));
                }
            }
        }
        return expected;
    }

    /** A short code for why a job waits, for the server's screen. */
    static int pauseCode(String pause) {
        return switch (pause) {
            case "no_power" -> 1;
            case "no_card" -> 2;
            case "waiting_player" -> 3;
            case "waiting_space" -> 4;
            case "no_network" -> 5;
            case "machine_busy" -> 6;
            case "no_machine" -> 7;
            case "dimension_upgrade" -> 8;
            default -> 0;
        };
    }
}
