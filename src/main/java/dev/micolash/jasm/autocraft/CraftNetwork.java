package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.CraftPlanner;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.core.JasmServerData;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.DeckView;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.transfer.TransferPortMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Registers the autocrafting messages, answers the Crafting Deck's screen, and keeps every Deck screen told, once a
 * second, whether its network can be reached and, on a Crafting Deck, what it can make and how its jobs are doing.
 * Nothing the screen sends is trusted.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class CraftNetwork {
    private static final int STATUS_EVERY = 20;
    private static final Map<DeckMenu, CraftPayloads.Status> SENT = new WeakHashMap<>();

    private CraftNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(CraftPayloads.Ask.TYPE, CraftPayloads.Ask.STREAM_CODEC,
                        (payload, context) -> ask((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.Start.TYPE, CraftPayloads.Start.STREAM_CODEC,
                        (payload, context) -> start((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.Cancel.TYPE, CraftPayloads.Cancel.STREAM_CODEC,
                        (payload, context) -> cancel((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.OpenServer.TYPE, CraftPayloads.OpenServer.STREAM_CODEC,
                        (payload, context) -> openServer((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.SetRule.TYPE, CraftPayloads.SetRule.STREAM_CODEC,
                        (payload, context) -> setRule((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.Ghost.TYPE, CraftPayloads.Ghost.STREAM_CODEC,
                        (payload, context) -> ghost((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.Trust.TYPE, CraftPayloads.Trust.STREAM_CODEC,
                        (payload, context) -> trust((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.PortName.TYPE, CraftPayloads.PortName.STREAM_CODEC,
                        (payload, context) -> portName((ServerPlayer) context.player(), payload))
                .playToServer(CraftPayloads.ProcessingGhost.TYPE, CraftPayloads.ProcessingGhost.STREAM_CODEC,
                        (payload, context) -> processingGhost((ServerPlayer) context.player(), payload))
                .playToClient(CraftPayloads.TerminalMachines.TYPE, CraftPayloads.TerminalMachines.STREAM_CODEC, CraftNetwork::onTerminalMachines)
                .playToClient(CraftPayloads.PortMachines.TYPE, CraftPayloads.PortMachines.STREAM_CODEC, CraftNetwork::onPortMachines)
                .playToClient(CraftPayloads.PortLinkedPlayer.TYPE, CraftPayloads.PortLinkedPlayer.STREAM_CODEC, CraftNetwork::onPortLinkedPlayer)
                .playToClient(CraftPayloads.TerminalDeck.TYPE, CraftPayloads.TerminalDeck.STREAM_CODEC, CraftNetwork::onTerminalDeck)
                .playToClient(CraftPayloads.ServerWaiting.TYPE, CraftPayloads.ServerWaiting.STREAM_CODEC, CraftNetwork::onServerWaiting)
                .playToClient(CraftPayloads.TrustView.TYPE, CraftPayloads.TrustView.STREAM_CODEC, CraftNetwork::onTrustView)
                .playToClient(CraftPayloads.Status.TYPE, CraftPayloads.Status.STREAM_CODEC, CraftNetwork::onStatus)
                .playToClient(CraftPayloads.Answer.TYPE, CraftPayloads.Answer.STREAM_CODEC, CraftNetwork::onAnswer);
    }

    // --- server ---

    private static @Nullable DeckMenu craftingMenu(ServerPlayer player, int containerId) {
        if (player.containerMenu instanceof DeckMenu menu && menu.containerId == containerId && menu.stillValid(player)
                && menu.isCrafting() && menu.dimensionAllowed()) {
            return menu;
        }
        return null;
    }

    public static void ask(ServerPlayer player, CraftPayloads.Ask payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        long now = player.level().getServer().getTickCount();
        Map<UUID, Long> asked = JasmServerData.of(player.level().getServer()).planAsked;
        // Planning is expensive, so a player gets at most a few plans a second.
        Long last = asked.get(player.getUUID());
        // The tick count starts again with each world, so an older time than now is from a world left earlier.
        if (menu == null || payload.target().isEmpty() || last != null && now >= last && now - last < 4) {
            return;
        }
        asked.put(player.getUUID(), now);
        Jobs.Preview preview = Jobs.preview(player, menu.deck(), payload.target(), clamp(payload.amount()), payload.server().orElse(null),
                payload.tree());
        CraftPlanner.Plan<GridKey> plan = preview.plan();
        List<DeckPayloads.Entry> crafts = new ArrayList<>();
        for (CraftPlanner.Step<GridKey> step : plan.steps()) {
            crafts.add(entry(step.pattern().output(), step.crafts() * step.pattern().outputCount()));
        }
        List<CraftPayloads.ServerView> servers = preview.servers().stream()
                .map(o -> new CraftPayloads.ServerView(o.pos(), o.memory(), o.parallel(), o.busy(), o.fits())).toList();
        PacketDistributor.sendToPlayer(player, new CraftPayloads.Answer(payload.containerId(), payload.target(), clamp(payload.amount()),
                plan.made(), preview.problem() == null ? "" : preview.problem(), entries(plan.taken()), entries(plan.missing()),
                limit(crafts), servers.subList(0, Math.min(64, servers.size())), preview.chosen(),
                payload.tree() ? Optional.of(treeView(plan.tree())) : Optional.empty()));
    }

    public static void start(ServerPlayer player, CraftPayloads.Start payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        if (menu == null || payload.target().isEmpty()) {
            return;
        }
        String problem = Jobs.start(player, menu.deck(), payload.target(), clamp(payload.amount()), payload.server().orElse(null));
        Notices.tell(player, problem == null
                ? Component.translatable("message.jasm.craft.started", clamp(payload.amount()), payload.target().toStack(1).getHoverName())
                : Component.translatable(problem), problem == null);
        SENT.remove(menu);
    }

    public static void cancel(ServerPlayer player, CraftPayloads.Cancel payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        if (menu != null && Jobs.cancel(player, menu.deck(), payload.server())) {
            Notices.good(player, Component.translatable("message.jasm.craft.cancelling"));
            SENT.remove(menu);
        }
    }

    /** Opens, from the Deck's job list, the screen of the server running one of its jobs. */
    public static void openServer(ServerPlayer player, CraftPayloads.OpenServer payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        CraftingServerBlockEntity crafting = jobServer(player, payload);
        UUID deckId = menu == null ? null : menu.deck().get(JasmComponents.DECK_ID.get());
        if (crafting != null && deckId != null) {
            int slot = menu.deckSlot();
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> CraftingServerMenu.remote(id, inventory, crafting, deckId, slot),
                    crafting.getDisplayName()));
        }
    }

    /** The server running one of the open Crafting Deck's jobs at {@code payload.server()}, if there is one. */
    public static @Nullable CraftingServerBlockEntity jobServer(ServerPlayer player, CraftPayloads.OpenServer payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        UUID deckId = menu == null ? null : menu.deck().get(JasmComponents.DECK_ID.get());
        if (deckId == null) {
            return null;
        }
        MinecraftServer server = player.level().getServer();
        UUID terminalId = menu.deck().get(JasmComponents.DECK_NETWORK.get());
        if (terminalId == null || !AutocraftState.get(server).isPaired(terminalId, player.getUUID(), deckId)) {
            return null;
        }
        for (AutocraftState.Job entry : AutocraftState.get(server).jobs()) {
            if (entry.finished() || !entry.requester().equals(player.getUUID()) || !entry.deck().map(deckId::equals).orElse(false)
                    || !entry.server().pos().equals(payload.server())) {
                continue;
            }
            ServerLevel level = server.getLevel(entry.server().dimension());
            if (level != null && level.isLoaded(payload.server())
                    && level.getBlockEntity(payload.server()) instanceof CraftingServerBlockEntity crafting
                    && crafting.job() != null && crafting.job().id().equals(entry.id()) && Jobs.onDeckNetwork(menu.deck(), level, payload.server())) {
                return crafting;
            }
        }
        return null;
    }

    /** Adds, changes or deletes one of the open Crafting Deck's rules, within its tier's limit. */
    public static boolean setRule(ServerPlayer player, CraftPayloads.SetRule payload) {
        DeckMenu menu = craftingMenu(player, payload.containerId());
        if (menu == null) {
            return false;
        }
        ItemStack deck = menu.deck();
        List<CraftRule> rules = new ArrayList<>(Rules.of(deck));
        int index = payload.index();
        if (payload.rule().isEmpty()) {
            if (index < 0 || index >= rules.size()) {
                return false;
            }
            JasmServerData.of(player.level().getServer()).forgetRule(rules.remove(index).id());
        } else {
            CraftRule rule = payload.rule().get().cleaned();
            if (index >= 0 && index < rules.size()) {
                // Editing keeps the rule's identity, so a job it started still counts as its own.
                rule = new CraftRule(rules.get(index).id(), rule.item(), rule.timed(), rule.threshold(), rule.seconds(), rule.amount(),
                        rule.enabled(),
                        rule.toPlayer());
                rules.set(index, rule);
            } else if (rules.size() < Rules.limit(deck)) {
                rules.add(new CraftRule(UUID.randomUUID(), rule.item(), rule.timed(), rule.threshold(), rule.seconds(), rule.amount(), rule.enabled(),
                        rule.toPlayer()));
            } else {
                Notices.bad(player, Component.translatable("message.jasm.rule.full", Rules.limit(deck)));
                return false;
            }
        }
        if (rules.isEmpty()) {
            deck.remove(JasmComponents.DECK_RULES.get());
        } else {
            deck.set(JasmComponents.DECK_RULES.get(), List.copyOf(rules));
        }
        return true;
    }

    /** Fills the open terminal's ghost grid, one example item per slot; nothing real moves. */
    public static boolean ghost(ServerPlayer player, CraftPayloads.Ghost payload) {
        if (!(player.containerMenu instanceof EncodingTerminalMenu menu) || menu.containerId != payload.containerId() || !menu.stillValid(player)) {
            return false;
        }
        if (payload.slot() < 0) {
            for (int i = 0; i < 9; i++) {
                menu.setGhost(i, i < payload.items().size() ? payload.items().get(i) : ItemStack.EMPTY);
            }
        } else if (payload.slot() < EncodingTerminalBlockEntity.AMOUNTS && !payload.items().isEmpty()) {
            menu.setGhost(payload.slot(), payload.items().getFirst());
        }
        return true;
    }

    /** Trusts or stops trusting a player at the open terminal. Owner only; checked in {@link TerminalAccess}. */
    public static void trust(ServerPlayer player, CraftPayloads.Trust payload) {
        if (player.containerMenu instanceof EncodingTerminalMenu menu && menu.containerId == payload.containerId() && menu.stillValid(player)
                && menu.terminal() != null) {
            if (payload.remove().isPresent()) {
                TerminalAccess.untrust(player, menu, menu.terminal(), payload.remove().get());
            } else if (!payload.name().isBlank()) {
                TerminalAccess.trust(player, menu, menu.terminal(), payload.name());
            }
        }
    }

    /** Fills the open terminal's processing grid and outputs from a recipe; nothing real moves. */
    public static boolean processingGhost(ServerPlayer player, CraftPayloads.ProcessingGhost payload) {
        if (!(player.containerMenu instanceof EncodingTerminalMenu menu) || menu.containerId != payload.containerId() || !menu.stillValid(player)) {
            return false;
        }
        menu.setProcessing(payload.inputs(), payload.outputs());
        return true;
    }

    private static void onServerWaiting(CraftPayloads.ServerWaiting payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof CraftingServerMenu menu && menu.containerId == payload.containerId()) {
            menu.setWaiting(payload.line().orElse(null));
        }
    }

    private static void onTerminalMachines(CraftPayloads.TerminalMachines payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof EncodingTerminalMenu menu && menu.containerId == payload.containerId()) {
            menu.setMachines(payload.machines());
        }
    }

    private static void onPortMachines(CraftPayloads.PortMachines payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof AccessPortMenu menu && menu.containerId == payload.containerId()) {
            menu.setMachines(payload.machines());
        }
    }

    private static void onPortLinkedPlayer(CraftPayloads.PortLinkedPlayer payload, IPayloadContext context) {
        var open = context.player().containerMenu;
        if (open.containerId != payload.containerId()) return;
        if (open instanceof AccessPortMenu menu) menu.setLinkedPlayer(payload.name());
        if (open instanceof TransferPortMenu menu) menu.setLinkedPlayer(payload.name());
    }

    private static void onTerminalDeck(CraftPayloads.TerminalDeck payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof EncodingTerminalMenu menu && menu.containerId == payload.containerId()) {
            menu.setDeckItems(payload.found(), payload.items());
        }
    }

    /** Names the open Access Port. */
    public static boolean portName(ServerPlayer player, CraftPayloads.PortName payload) {
        if (player.containerMenu instanceof AccessPortMenu menu && menu.containerId == payload.containerId() && menu.stillValid(player)
                && menu.port() != null) {
            menu.port().setLabel(payload.name());
            return true;
        }
        return false;
    }

    private static void onTrustView(CraftPayloads.TrustView payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof EncodingTerminalMenu menu && menu.containerId == payload.containerId()) {
            menu.setTrustView(payload.owner(), payload.trust());
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SENT.clear();
    }

    private static long clamp(long amount) {
        return Math.clamp(amount, 1, JasmConfig.MAX_REQUEST.getAsInt());
    }

    /** A line of the answer. A fluid or material travels as its marker item, with the millibuckets or units as the count. */
    private static DeckPayloads.Entry entry(GridKey key, long count) {
        ItemResource shown = key instanceof GridKey.Fluid fluid ? ItemResource.of(FluidMarkerItem.of(fluid.resource()))
                : key instanceof GridKey.Material material ? ItemResource.of(MaterialMarkerItem.of(material.key())) : key.item();
        return new DeckPayloads.Entry(shown, count);
    }

    /** The recorded tree for the screen; too many boxes or lines send none (the screen says so). */
    private static CraftPayloads.TreeView treeView(CraftPlanner.Tree<GridKey> tree) {
        if (tree.boxes().size() > CraftPayloads.MAX_TREE_BOXES || tree.links().size() > CraftPayloads.MAX_TREE_LINKS) {
            return new CraftPayloads.TreeView(true, -1, List.of(), List.of());
        }
        List<CraftPayloads.TreeBox> boxes = new ArrayList<>();
        for (CraftPlanner.Box<GridKey> box : tree.boxes()) {
            boxes.add(new CraftPayloads.TreeBox(entry(box.key(), box.amount()).key(), box.kind().ordinal(), box.amount(), box.crafts()));
        }
        List<CraftPayloads.TreeLink> links = tree.links().stream().map(l -> new CraftPayloads.TreeLink(l.from(), l.to())).toList();
        return new CraftPayloads.TreeView(false, tree.root(), boxes, links);
    }

    private static List<DeckPayloads.Entry> entries(Map<GridKey, Long> map) {
        return limit(map.entrySet().stream().map(e -> entry(e.getKey(), e.getValue())).toList());
    }

    private static <T> List<T> limit(List<T> list) {
        return list.size() <= CraftPayloads.MAX_LIST ? list : list.subList(0, CraftPayloads.MAX_LIST);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        boolean due = server.getTickCount() % STATUS_EVERY == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CraftPayloads.Status status = statusToSend(player, due);
            if (status != null) {
                PacketDistributor.sendToPlayer(player, status);
            }
        }
    }

    /** What the player's open Deck screen should hear now, or null when it has nothing new. */
    public static CraftPayloads.@Nullable Status statusToSend(ServerPlayer player, boolean due) {
        // A Deck screen that just opened hears at once; after that, once a second.
        if (player.containerMenu instanceof DeckMenu menu && (due || !SENT.containsKey(menu)) && menu.stillValid(player)) {
            CraftPayloads.Status status = status(player, menu);
            if (!status.equals(SENT.get(menu))) {
                SENT.put(menu, status);
                return status;
            }
        }
        return null;
    }

    /** What the Deck's screen should know right now. A normal Deck hears only whether its network can be reached. */
    public static CraftPayloads.Status status(ServerPlayer player, DeckMenu menu) {
        ItemStack deck = menu.deck();
        MinecraftServer server = player.level().getServer();
        boolean crafting = menu.isCrafting();
        List<CraftPayloads.JobView> jobs = crafting ? jobsOf(server, player, deck) : List.of();
        if (!deck.has(JasmComponents.DECK_NETWORK.get())) {
            return new CraftPayloads.Status(menu.containerId, 0, List.of(), jobs, Map.of());
        }
        UUID terminalId = deck.get(JasmComponents.DECK_NETWORK.get());
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (terminalId == null || deckId == null || !AutocraftState.get(server).isPaired(terminalId, player.getUUID(), deckId)) {
            return new CraftPayloads.Status(menu.containerId, 0, List.of(), List.of(), Map.of());
        }
        EncodingTerminalBlockEntity terminal = Jobs.terminalOf(server, deck);
        CableNetwork network = terminal == null ? null : Networks.at((ServerLevel) terminal.getLevel(), terminal.getBlockPos());
        if (network == null || !terminal.running()) {
            return new CraftPayloads.Status(menu.containerId, 1, List.of(), jobs, crafting ? Rules.stalled(server, deck) : Map.of());
        }
        if (!crafting) {
            return new CraftPayloads.Status(menu.containerId, 2, List.of(), List.of(), Map.of());
        }
        List<ItemResource> craftable = new ArrayList<>();
        for (Card card : Jobs.cards(network, player)) {
            // a material can't be asked for, only used by other cards
            if (MaterialMarkerItem.isMarker(card.result())) {
                continue;
            }
            ItemResource out = ItemResource.of(card.result());
            if (!craftable.contains(out)) {
                craftable.add(out);
            }
        }
        return new CraftPayloads.Status(menu.containerId, 2, limit(craftable), jobs, Rules.stalled(server, deck));
    }

    /** This Deck's jobs, on servers that are loaded and on its network. */
    static List<CraftPayloads.JobView> jobsOf(MinecraftServer server, ServerPlayer player, ItemStack deck) {
        List<CraftPayloads.JobView> views = new ArrayList<>();
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (deckId == null) {
            return views;
        }
        for (AutocraftState.Job entry : AutocraftState.get(server).jobs()) {
            if (entry.finished() || !entry.requester().equals(player.getUUID()) || !entry.deck().map(deckId::equals).orElse(false)) {
                continue;
            }
            ServerLevel level = server.getLevel(entry.server().dimension());
            BlockPos pos = entry.server().pos();
            if (level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CraftingServerBlockEntity crafting
                    && crafting.job() != null && crafting.job().id().equals(entry.id()) && Jobs.onDeckNetwork(deck, level, pos)) {
                CraftingJob job = crafting.job();
                ItemResource target = job.target() == null ? ItemResource.EMPTY : ItemResource.of(job.target().create());
                views.add(new CraftPayloads.JobView(pos, target, job.amount(), job.phase().ordinal(), Math.round(job.progress() * 1000),
                        job.pause().code(), Optional.ofNullable(job.waiting()).map(CraftingJob.Waiting::line)));
            }
            if (views.size() >= 64) {
                break;
            }
        }
        return views;
    }

    // --- client ---

    private static @Nullable DeckView view(IPayloadContext context, int containerId) {
        if (context.player().containerMenu instanceof DeckMenu menu && menu.containerId == containerId) {
            return menu.view();
        }
        return null;
    }

    private static void onStatus(CraftPayloads.Status payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyCraftStatus(payload.network(), Set.copyOf(payload.craftable()), payload.jobs(), payload.stalled());
        }
    }

    private static void onAnswer(CraftPayloads.Answer payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyAnswer(Objects.requireNonNull(payload));
        }
    }
}
