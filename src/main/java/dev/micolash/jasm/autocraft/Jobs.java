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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
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
        if (id == null) {
            return null;
        }
        Optional<AutocraftState.Terminal> entry = AutocraftState.get(server).terminal(id);
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
    public static List<RecipeCard> cards(CableNetwork network, ServerPlayer player) {
        Set<RecipeCard> cards = new LinkedHashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            if (MachineAccess.canUse(rack, player)) {
                cards.addAll(rack.cards());
            }
        }
        return List.copyOf(cards);
    }

    /** Every card on the network, whoever may use them: what a running job may keep using. */
    static Set<RecipeCard> allCards(CableNetwork network) {
        Set<RecipeCard> cards = new HashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            cards.addAll(rack.cards());
        }
        return cards;
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
        EncodingTerminalBlockEntity terminal = terminalOf(player.level().getServer(), deck);
        if (terminal == null) {
            return new Preview(empty, List.of(), -1, deck.has(JasmComponents.DECK_NETWORK.get())
                    ? "message.jasm.craft.network_unreachable" : "message.jasm.craft.not_paired");
        }
        if (!MachineAccess.canUse(terminal, player)) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.no_access");
        }
        if (!terminal.running()) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.network_unreachable");
        }
        ServerLevel level = (ServerLevel) terminal.getLevel();
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        if (network == null) {
            return new Preview(empty, List.of(), -1, "message.jasm.craft.network_unreachable");
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        DeckStorage.checkAll(store, deck, player);
        Map<ItemResource, Long> stock = DeckStorage.contents(store, deck);
        CardBook book = new CardBook(level, cards(network, player), stock.keySet());
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
            case NO_PATTERN -> "message.jasm.craft.no_card";
            case MISSING -> "message.jasm.craft.missing";
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
            steps.add(CraftingJob.step(((CardBook.CardPattern) step.pattern()).card().card(), step.crafts()));
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
            job.pause = "no_power";
            return;
        }
        CableNetwork network = Networks.at(level, server.getBlockPos());
        Set<RecipeCard> cards = network == null ? Set.of() : allCards(network);
        boolean changed = finishCrafts(level, job, record, store);
        boolean started = false;
        boolean missingCard = false;
        if (job.phase == CraftingJob.Phase.CRAFTING) {
            int free = server.parallel() - job.running.size();
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
        if (started || changed) {
            server.setChanged();
        }
        job.pause = missingCard && job.running.isEmpty() ? "no_card" : "";
        boolean stepsLeft = job.steps.stream().anyMatch(s -> s.left > 0);
        if (job.running.isEmpty()) {
            if (job.phase == CraftingJob.Phase.CANCELLING || !stepsLeft) {
                job.phase = CraftingJob.Phase.RETURNING;
                job.pause = "";
                server.setChanged();
            } else if (!missingCard && !started && ++job.stuck >= STUCK_TICKS) {
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
        prepareOpenDeck(player, deck);
        DeckStorage.checkAll(store, deck, player);
        for (Map.Entry<ItemResource, Long> held : List.copyOf(record.contents().entrySet())) {
            // Move it in two steps under the requester's name: onto the wafers, then off the job, so both records agree.
            long stored = DeckStorage.depositAmount(store, deck, held.getKey(), held.getValue(), player);
            if (stored > 0) {
                store.extract(record, held.getKey(), stored, false, player);
            }
        }
        refreshOpenDeck(player, deck);
        if (record.contents().isEmpty()) {
            finish(level, server, job);
        } else {
            // The Deck's screen shows it: a banner, and the job in its list.
            job.pause = "waiting_space";
        }
    }

    /** The Crafting Deck with this identity in the player's inventory. */
    static ItemStack findDeck(ServerPlayer player, @Nullable UUID deckId) {
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
        server.setJob(null);
        AutocraftState.get(level.getServer()).finishJob(job.id);
    }

    // --- players ---

    /** Stops a job: no new crafts; running ones finish; then everything left goes back. The requester or the server's users may. */
    public static boolean cancel(ServerPlayer player, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        if (job == null || job.phase != CraftingJob.Phase.CRAFTING
                || !(job.requester.equals(player.getUUID()) || MachineAccess.canUse(server, player))) {
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
     * requester, and anyone who may use the server, may. Returns how many items came out.
     */
    public static long collect(ServerPlayer player, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.get(player.level().getServer());
        if (job == null || job.phase != CraftingJob.Phase.RETURNING
                || !(job.requester.equals(player.getUUID()) || MachineAccess.canUse(server, player))) {
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
        long moved = 0;
        Inventory inventory = player.getInventory();
        for (Map.Entry<ItemResource, Long> held : List.copyOf(record.contents().entrySet())) {
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
    private static void prepareOpenDeck(ServerPlayer player, ItemStack deck) {
        if (player.containerMenu instanceof DeckMenu menu && menu.deck() == deck) {
            menu.wafers().flush();
        }
    }

    private static void refreshOpenDeck(ServerPlayer player, ItemStack deck) {
        if (player.containerMenu instanceof DeckMenu menu && menu.deck() == deck) {
            menu.wafers().reload();
            DeckViewTracker.markDirty(menu);
        }
    }

    /** A short code for why a job waits, for the server's screen. */
    static int pauseCode(String pause) {
        return switch (pause) {
            case "no_power" -> 1;
            case "no_card" -> 2;
            case "waiting_player" -> 3;
            case "waiting_space" -> 4;
            case "no_network" -> 5;
            default -> 0;
        };
    }
}
