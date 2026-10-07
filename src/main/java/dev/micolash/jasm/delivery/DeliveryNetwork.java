package dev.micolash.jasm.delivery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.core.JasmServerData;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckNetwork;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckViewTracker;
import dev.micolash.jasm.registry.JasmTriggers;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/** Registers the Deck to Deck messages and handles them. The server checks everything again; the window only asks. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DeliveryNetwork {
    private DeliveryNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(DeliveryPayloads.Watch.TYPE, DeliveryPayloads.Watch.STREAM_CODEC,
                        (payload, context) -> watch((ServerPlayer) context.player(), payload))
                .playToServer(DeliveryPayloads.AskPeople.TYPE, DeliveryPayloads.AskPeople.STREAM_CODEC,
                        (payload, context) -> askPeople((ServerPlayer) context.player(), payload.containerId()))
                .playToServer(DeliveryPayloads.Send.TYPE, DeliveryPayloads.Send.STREAM_CODEC,
                        (payload, context) -> send((ServerPlayer) context.player(), payload))
                .playToServer(DeliveryPayloads.Cancel.TYPE, DeliveryPayloads.Cancel.STREAM_CODEC,
                        (payload, context) -> cancel((ServerPlayer) context.player(), payload))
                .playToServer(DeliveryPayloads.StoreAll.TYPE, DeliveryPayloads.StoreAll.STREAM_CODEC,
                        (payload, context) -> storeAll((ServerPlayer) context.player(), payload.containerId()))
                .playToClient(DeliveryPayloads.Status.TYPE, DeliveryPayloads.Status.STREAM_CODEC, DeliveryNetwork::onStatus)
                .playToClient(DeliveryPayloads.People.TYPE, DeliveryPayloads.People.STREAM_CODEC, DeliveryNetwork::onPeople);
    }

    // --- server ---

    private static @Nullable DeckMenu menu(ServerPlayer player, int containerId) {
        DeckMenu menu = DeckNetwork.openMenu(player, containerId);
        return menu != null && DeckNetwork.allow(player) ? menu : null;
    }

    static void watch(ServerPlayer player, DeliveryPayloads.Watch payload) {
        Map<UUID, Integer> watchers = JasmServerData.of(player.level().getServer()).sendWatchers;
        if (!payload.on()) {
            watchers.remove(player.getUUID());
            return;
        }
        if (menu(player, payload.containerId()) == null) return;
        watchers.put(player.getUUID(), payload.containerId());
        sendStatus(player, payload.containerId());
    }

    /** Each second: every player with the window open hears about their trips. Players who closed the Deck drop out. */
    static void pushStatus(MinecraftServer server) {
        Map<UUID, Integer> watchers = JasmServerData.of(server).sendWatchers;
        for (Iterator<Map.Entry<UUID, Integer>> it = watchers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !(player.containerMenu instanceof DeckMenu menu) || menu.containerId != entry.getValue()) {
                it.remove();
            } else {
                sendStatus(player, entry.getValue());
            }
        }
    }

    /** {@code player}'s trips, going out and coming in, soonest first. */
    public static List<DeliveryPayloads.TripView> trips(MinecraftServer server, UUID player) {
        long now = Deliveries.now(server);
        List<DeliveryPayloads.TripView> views = new ArrayList<>();
        for (DeliveryState.Trip trip : DeliveryState.get(server).trips()) {
            boolean outgoing = trip.from().equals(player) && !trip.returning();
            if (!outgoing && !trip.to().equals(player)) continue;
            ItemStack icon = trip.items().values().isEmpty() ? ItemStack.EMPTY : trip.items().values().getFirst().copyWithCount(1);
            List<ItemStack> stacks = trip.items().values().stream().limit(DeliveryPayloads.MAX_STACKS).map(ItemStack::copy).toList();
            views.add(new DeliveryPayloads.TripView(trip.id(), outgoing, trip.returning(), outgoing ? trip.toName() : trip.fromName(), icon,
                    trip.count(), (int) Math.max(0, trip.arrive() - now), (int) Math.max(1, trip.arrive() - trip.start()), stacks));
        }
        views.sort((a, b) -> Integer.compare(a.left(), b.left()));
        return views.size() > DeliveryPayloads.MAX_TRIPS ? views.subList(0, DeliveryPayloads.MAX_TRIPS) : views;
    }

    private static void sendStatus(ServerPlayer player, int containerId) {
        PacketDistributor.sendToPlayer(player, new DeliveryPayloads.Status(containerId, trips(player.level().getServer(), player.getUUID())));
    }

    /** What stops this Deck sending to anyone at all, before a player is picked. */
    static Deliveries.Refusal overall(ServerPlayer player, DeckMenu menu) {
        ItemStack deck = menu.deck();
        if (!Feature.DECK_TO_DECK.on()) return Deliveries.Refusal.TURNED_OFF;
        if (Deliveries.pairedTerminal(player.level().getServer(), player, deck) == null) return Deliveries.Refusal.NOT_PAIRED;
        if (!menu.dimensionAllowed()) return Deliveries.Refusal.LOCKED;
        long count = menu.sendItems().stream().mapToLong(ItemStack::getCount).sum();
        if (count == 0) return Deliveries.Refusal.EMPTY;
        if (DeliveryState.get(player.level().getServer()).outgoing(player.getUUID()) >= Deliveries.MAX_TRIPS) return Deliveries.Refusal.TOO_MANY;
        if (Deliveries.cost(count) > DeckItem.energy(deck)) return Deliveries.Refusal.NO_CHARGE;
        return Deliveries.Refusal.NONE;
    }

    static void askPeople(ServerPlayer player, int containerId) {
        DeckMenu menu = menu(player, containerId);
        if (menu == null) return;
        List<DeliveryPayloads.Person> people = new ArrayList<>();
        Deliveries.Refusal overall = overall(player, menu);
        if (overall == Deliveries.Refusal.NONE || overall == Deliveries.Refusal.NO_CHARGE || overall == Deliveries.Refusal.TOO_MANY) {
            for (Deliveries.Member member : Deliveries.members(player, menu.deck())) {
                if (people.size() >= DeliveryPayloads.MAX_PEOPLE) break;
                ServerPlayer other = member.player();
                long ticks = Deliveries.travelTicks(player.level(), player.position(), other.level(), other.position());
                people.add(new DeliveryPayloads.Person(other.getUUID(), other.getName().getString(), (int) Math.min(Integer.MAX_VALUE, ticks),
                        Deliveries.check(player, menu.deck(), member).ordinal()));
            }
            people.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        }
        PacketDistributor.sendToPlayer(player, new DeliveryPayloads.People(containerId, people, overall.ordinal()));
    }

    static void send(ServerPlayer player, DeliveryPayloads.Send payload) {
        DeckMenu menu = menu(player, payload.containerId());
        if (menu == null) return;
        Deliveries.Refusal refusal = menu.dimensionAllowed()
                ? Deliveries.send(player, menu.deck(), payload.to(), menu.sendItems())
                : Deliveries.Refusal.LOCKED;
        if (refusal == Deliveries.Refusal.NONE) {
            menu.clearSend();
            JasmTriggers.DECK_DELIVERY.get().trigger(player);
            ServerPlayer to = player.level().getServer().getPlayerList().getPlayer(payload.to());
            Notices.good(player, Component.translatable("screen.jasm.send.sent", to == null ? "" : to.getName().getString()));
        } else {
            Notices.bad(player, Component.translatable(refusal.key()));
        }
        menu.broadcastChanges();
        DeckViewTracker.markDirty(menu);
        sendStatus(player, payload.containerId());
    }

    static void cancel(ServerPlayer player, DeliveryPayloads.Cancel payload) {
        if (menu(player, payload.containerId()) == null) return;
        if (Deliveries.cancel(player.level().getServer(), player.getUUID(), payload.trip())) {
            Notices.good(player, Component.translatable("screen.jasm.send.cancelled"));
        }
        sendStatus(player, payload.containerId());
    }

    /** Moves the inbox onto the Deck's wafers, as far as they and the charge allow. What doesn't fit stays. */
    public static void storeAll(ServerPlayer player, int containerId) {
        DeckMenu menu = menu(player, containerId);
        if (menu == null) return;
        MinecraftServer server = player.level().getServer();
        DeliveryState state = DeliveryState.get(server);
        WaferStore store = WaferStore.get(server);
        long moved = 0;
        for (ItemStack stack : state.inbox(player.getUUID())) {
            if (stack.isEmpty() || !DeckMenu.allowedInGrid(stack)) continue;
            moved += DeckStorage.depositQuietly(store, menu.deck(), stack, player, DeckStorage.Excess.VOID);
        }
        state.inboxChanged(player.getUUID());
        if (moved == 0 && state.hasInbox(player.getUUID())) Notices.bad(player, Component.translatable("screen.jasm.send.store_none"));
        menu.broadcastChanges();
        DeckViewTracker.markDirty(menu);
    }

    // --- client ---

    private static void onStatus(DeliveryPayloads.Status payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof DeckMenu menu && menu.containerId == payload.containerId()) {
            menu.deliveries().applyStatus(payload.trips(), context.player().level().getGameTime());
        }
    }

    private static void onPeople(DeliveryPayloads.People payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof DeckMenu menu && menu.containerId == payload.containerId()) {
            menu.deliveries().applyPeople(payload);
        }
    }
}
