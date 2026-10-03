package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.autocraft.Jobs.Preview;
import dev.micolash.jasm.autocraft.Jobs.ServerOption;
import dev.micolash.jasm.core.CraftPlanner;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Finding a Deck's network, and planning and starting crafting requests. */
final class JobPlanning {
    private JobPlanning() {}

    /** The network a paired Deck belongs to, if its terminal stands in a loaded spot and has power. */
    public static @Nullable EncodingTerminalBlockEntity terminalOf(MinecraftServer server, ItemStack deck) {
        UUID id = deck.get(JasmComponents.DECK_NETWORK.get());
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (id == null || deckId == null) {
            return null;
        }
        if (!AutocraftState.get(server).isActive(id, deckId)) {
            return null;
        }
        return terminalById(server, id);
    }

    /** The Encoding Terminal with this identity, if it stands in a loaded spot. */
    public static @Nullable EncodingTerminalBlockEntity terminalById(MinecraftServer server, UUID id) {
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

    /** Whether the block at {@code pos} is on the network this Deck is paired with. */
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

    /** Whether a processing card has a machine it can use: one of its ports stands on the network and faces something. */
    static boolean reachable(ServerLevel level, @Nullable CableNetwork network, ProcessingCard card) {
        for (Machines.At at : card.spots()) {
            if (Machines.reach(level, network, at) != null) {
                return true;
            }
        }
        return false;
    }

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
            return new Preview(empty, List.of(), -1,
                    terminal.stopped() ? "message.jasm.craft.network_full" : "message.jasm.craft.network_unreachable");
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
        AutocraftState state = AutocraftState.get(level.getServer());
        WaferRecord record = store.createJob(player, serial -> state.jobs().stream().anyMatch(j -> j.serial() == serial));
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
        state.addJob(new AutocraftState.Job(id, record.serial(), record.id(), new ArchiveRecord.Placement(level.dimension(), server.getBlockPos()),
                player.getUUID(), player.getPlainTextName(), Optional.of(deckId), false, Optional.ofNullable(rule)));
        state.saveNow(level.getServer());
        JobReturns.refreshOpenDeck(player, deck);
        return null;
    }

    private record Taken(WaferRecord wafer, ItemResource key, long amount) {}
}
