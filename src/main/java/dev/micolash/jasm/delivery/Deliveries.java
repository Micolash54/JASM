package dev.micolash.jasm.delivery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.dock.DockingStationBlockEntity;
import dev.micolash.jasm.dock.DockingStations;
import dev.micolash.jasm.dock.DeckReach;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.NetworkTrust;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.LenientListCodec;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Deck to Deck: who a Deck can send to, how long a trip takes and what it costs, and trips arriving. Arrivals are
 * checked once a second.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Deliveries {
    /** Trips one player can have on their way at once. */
    public static final int MAX_TRIPS = 10;

    private Deliveries() {}

    /** Why a send can't go ahead; {@link #NONE} when it can. */
    public enum Refusal {
        NONE,
        NOT_PAIRED,
        NOT_ON_NETWORK,
        EMPTY,
        TOO_MANY,
        NEEDS_UPGRADE,
        THEY_NEED_UPGRADE,
        NO_CHARGE,
        LOCKED,
        TURNED_OFF;

        public String key() {
            return "screen.jasm.send.refused." + name().toLowerCase(Locale.ROOT);
        }

        public static Refusal byId(int id) {
            return values()[Math.floorMod(id, values().length)];
        }
    }

    /** Someone on the sender's network who is online, and the Deck they have paired there. */
    public record Member(ServerPlayer player, UUID deck) {}

    /** Server time that keeps counting across restarts. */
    public static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** FE a send of {@code items} items costs the sender: twice what moving them in or out of the Deck would. */
    public static long cost(long items) {
        return items * 2L * JasmConfig.DECK_ENERGY_PER_ITEM.getAsInt();
    }

    /**
     * Ticks a trip takes between two spots. Positions in other dimensions are measured on the Overworld's scale
     * (the Nether counts 8x), and crossing between dimensions adds a fixed extra.
     */
    public static long travelTicks(Level fromLevel, Vec3 from, Level toLevel, Vec3 to) {
        double fromScale = fromLevel.dimensionType().coordinateScale();
        double toScale = toLevel.dimensionType().coordinateScale();
        double dx = from.x * fromScale - to.x * toScale;
        double dz = from.z * fromScale - to.z * toScale;
        double dy = from.y - to.y;
        double seconds = Tuning.SEND_BASE_SECONDS
                + Math.sqrt(dx * dx + dy * dy + dz * dz) / JasmConfig.SEND_BLOCKS_PER_SECOND.getAsInt();
        if (!fromLevel.dimension().equals(toLevel.dimension())) seconds += Tuning.SEND_DIMENSION_SECONDS;
        seconds = Math.min(seconds, Tuning.SEND_MAX_SECONDS);
        return Math.max(1, Math.round(seconds * 20));
    }

    /** The terminal identity the Deck is paired with for {@code player}, or null when it isn't paired. */
    static @Nullable UUID pairedTerminal(MinecraftServer server, ServerPlayer player, ItemStack deck) {
        UUID terminal = deck.get(JasmComponents.DECK_NETWORK.get());
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (terminal == null || deckId == null || !AutocraftState.get(server).isPaired(terminal, player.getUUID(), deckId)) return null;
        return terminal;
    }

    /**
     * Everyone {@code sender} can send to, the sender left out: players on the network their Deck is paired with,
     * then players on any network whose owner trusts the sender. Each player is listed once.
     */
    public static List<Member> members(ServerPlayer sender, ItemStack deck) {
        List<Member> members = new ArrayList<>(pairedMembers(sender, deck));
        addTrustedMembers(sender, members);
        return members;
    }

    /**
     * Players online with a Deck paired on a network whose owner trusts {@code sender} (or that the sender owns),
     * wherever the sender's own Deck is paired. Trust is checked again each time, from the world's trust lists when
     * the network's terminal isn't loaded.
     */
    private static void addTrustedMembers(ServerPlayer sender, List<Member> members) {
        MinecraftServer server = sender.level().getServer();
        Map<UUID, Boolean> trusting = new HashMap<>();
        for (AutocraftState.Pairing pairing : AutocraftState.get(server).pairings()) {
            if (pairing.player().equals(sender.getUUID()) || listed(members, pairing.player())) continue;
            ServerPlayer player = receiver(server, pairing);
            if (player == null) continue;
            if (trusting.computeIfAbsent(pairing.terminal(), id -> trusts(server, id, sender))) members.add(new Member(player, pairing.deck()));
        }
    }

    /**
     * Whether terminal {@code terminalId} belongs to {@code player} or to someone who trusts them on its network,
     * right now. While it isn't loaded (or has been mined), the world's trust lists answer.
     */
    static boolean trusts(MinecraftServer server, UUID terminalId, ServerPlayer player) {
        EncodingTerminalBlockEntity terminal = Jobs.terminalById(server, terminalId);
        if (terminal != null) return terminal.owner() != null && MachineAccess.canUse(terminal, player);
        return NetworkTrust.get(server).trusts(terminalId, player.getUUID());
    }

    /**
     * Whether a Deck paired at {@code terminalId} may still send there: its player owns the network or is trusted on
     * it now. A terminal not seen since trust moved into the world keeps the old rule, where being paired is enough:
     * its list can't have changed since.
     */
    private static boolean stillTrusted(MinecraftServer server, UUID terminalId, ServerPlayer player) {
        if (Jobs.terminalById(server, terminalId) == null && !NetworkTrust.get(server).known(terminalId)) return true;
        return trusts(server, terminalId, player);
    }

    /**
     * Players online with a Deck paired on the same network as {@code deck}, the sender left out, while the sender
     * still owns that network or is trusted on it. While the network's terminal is loaded that means every terminal on
     * it; otherwise just the Deck's own terminal.
     */
    private static List<Member> pairedMembers(ServerPlayer sender, ItemStack deck) {
        MinecraftServer server = sender.level().getServer();
        UUID terminal = pairedTerminal(server, sender, deck);
        if (terminal == null || !stillTrusted(server, terminal, sender)) return List.of();
        Set<UUID> group = Set.of(terminal);
        EncodingTerminalBlockEntity block = Jobs.terminalById(server, terminal);
        if (block != null && block.getLevel() instanceof ServerLevel level) {
            CableNetwork network = Networks.at(level, block.getBlockPos());
            if (network != null) {
                group = Set.copyOf(network.machines(EncodingTerminalBlockEntity.class).stream().map(EncodingTerminalBlockEntity::ensureId).toList());
            }
        }
        List<Member> members = new ArrayList<>();
        for (AutocraftState.Pairing pairing : AutocraftState.get(server).pairingsOn(group)) {
            if (pairing.player().equals(sender.getUUID()) || listed(members, pairing.player())) continue;
            ServerPlayer player = receiver(server, pairing);
            if (player != null) members.add(new Member(player, pairing.deck()));
        }
        return members;
    }

    private static boolean listed(List<Member> members, UUID player) {
        for (Member member : members) if (member.player().getUUID().equals(player)) return true;
        return false;
    }

    /**
     * Who receives for a paired Deck: its player while online, or, while the Deck sits in a working Docking Station, a stand-in
     * for them at the dock (so a player away from the game can still be sent to). Null when neither.
     */
    private static @Nullable ServerPlayer receiver(MinecraftServer server, AutocraftState.Pairing pairing) {
        ServerPlayer online = server.getPlayerList().getPlayer(pairing.player());
        if (online != null) return online;
        DeckReach reach = DeckReach.find(server, pairing.player(), pairing.deck());
        return reach != null && reach.docked() ? reach.actor() : null;
    }

    /** Whether {@code player} carries (or wears, or has docked) the Deck with identity {@code deckId} and it has a Dimension Upgrade. */
    public static boolean carriesUpgradedDeck(ServerPlayer player, UUID deckId) {
        DeckReach reach = DeckReach.find(player.level().getServer(), player.getUUID(), deckId);
        return reach != null && DeckItem.hasDimensionUpgrade(reach.deck());
    }

    /** What stops {@code sender} sending to {@code member} right now, apart from what is in the grid. */
    public static Refusal check(ServerPlayer sender, ItemStack deck, Member member) {
        if (!DeckItem.worksIn(deck, sender.level())) return Refusal.LOCKED;
        if (DeliveryState.get(sender.level().getServer()).outgoing(sender.getUUID()) >= MAX_TRIPS) return Refusal.TOO_MANY;
        if (!sender.level().dimension().equals(member.player().level().dimension())) {
            if (!DeckItem.hasDimensionUpgrade(deck)) return Refusal.NEEDS_UPGRADE;
            if (!carriesUpgradedDeck(member.player(), member.deck())) return Refusal.THEY_NEED_UPGRADE;
        }
        return Refusal.NONE;
    }

    /**
     * Sends {@code items} from {@code sender} to the network member {@code to}. On success the charge is taken and
     * the trip is on its way; the caller then empties the grid. Nothing changes when it is refused.
     */
    public static Refusal send(ServerPlayer sender, ItemStack deck, UUID to, List<ItemStack> items) {
        if (!Feature.DECK_TO_DECK.on()) return Refusal.TURNED_OFF;
        MinecraftServer server = sender.level().getServer();
        // Who may be sent to is worked out again on every send, trust included: a list shown earlier proves nothing.
        Member member = members(sender, deck).stream().filter(m -> m.player().getUUID().equals(to)).findFirst().orElse(null);
        if (member == null) return pairedTerminal(server, sender, deck) == null ? Refusal.NOT_PAIRED : Refusal.NOT_ON_NETWORK;
        List<ItemStack> sent = items.stream().filter(s -> !s.isEmpty()).map(ItemStack::copy).toList();
        if (sent.isEmpty()) return Refusal.EMPTY;
        Refusal refusal = check(sender, deck, member);
        if (refusal != Refusal.NONE) return refusal;
        long cost = cost(sent.stream().mapToLong(ItemStack::getCount).sum());
        if (cost > DeckItem.energy(deck)) return Refusal.NO_CHARGE;
        if (cost > 0) deck.set(JasmComponents.ENERGY.get(), (int) (DeckItem.energy(deck) - cost));
        long now = now(server);
        ServerPlayer target = member.player();
        long ticks = travelTicks(sender.level(), sender.position(), target.level(), target.position());
        DeliveryState.get(server).addTrip(new DeliveryState.Trip(UUID.randomUUID(), sender.getUUID(), sender.getName().getString(),
                to, target.getName().getString(), LenientListCodec.Lenient.of(sent), now, now + ticks, false));
        return Refusal.NONE;
    }

    /** {@code player} calls back a trip they sent: it turns round and comes to their own inbox. */
    public static boolean cancel(MinecraftServer server, UUID player, UUID tripId) {
        DeliveryState state = DeliveryState.get(server);
        DeliveryState.Trip trip = state.trip(tripId);
        if (trip == null || trip.returning() || !trip.from().equals(player)) return false;
        DeliveryState.Trip back = trip.turnedRound(now(server));
        state.replaceTrip(back);
        arrive(server, state, back);
        return true;
    }

    /**
     * Hands a trip over if its whole load fits in the inbox; otherwise it waits there for room. A receiver whose Deck sits
     * in a dock gets it on that Deck instead, never in the inbox: see {@link #intoDock}.
     */
    private static boolean arrive(MinecraftServer server, DeliveryState state, DeliveryState.Trip trip) {
        List<DockingStationBlockEntity> docks = DockingStations.of(trip.to());
        if (!docks.isEmpty()) return intoDock(server, state, trip, docks);
        if (!state.deliver(trip.to(), trip.items())) return false;
        state.removeTrip(trip.id());
        return true;
    }

    /**
     * A trip to a player with a docked Deck: what fits goes onto that Deck, like into any full storage, and the rest
     * stays on the trip and waits for room, as a trip waits for room in a full inbox. Nothing goes to the inbox, and
     * nothing that arrives is destroyed by a Void row. What went in leaves the trip at once, so it is never handed over
     * twice. Returns whether the whole trip is handed over.
     */
    private static boolean intoDock(MinecraftServer server, DeliveryState state, DeliveryState.Trip trip, List<DockingStationBlockEntity> docks) {
        WaferStore store = WaferStore.ifOpen(server);
        if (store == null) return false;
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack stack : trip.items().values()) left.add(stack.copy());
        boolean stored = false;
        for (DockingStationBlockEntity dock : docks) {
            DeckReach reach = DeckReach.find(server, trip.to(), dock.deck().get(JasmComponents.DECK_ID.get()));
            if (reach == null || reach.dock() != dock || !DeckItem.worksIn(reach.deck(), reach.actor().level())) continue;
            for (ItemStack stack : left) {
                if (!stack.isEmpty() && DeckMenu.allowedToSend(stack)
                        && DeckStorage.depositQuietly(store, reach.deck(), stack, reach.actor()) > 0) stored = true;
            }
        }
        if (!stored) return false;
        left.removeIf(ItemStack::isEmpty);
        if (left.isEmpty() && trip.items().raw().isEmpty()) {
            state.removeTrip(trip.id());
            return true;
        }
        state.replaceTrip(new DeliveryState.Trip(trip.id(), trip.from(), trip.fromName(), trip.to(), trip.toName(),
                new LenientListCodec.Lenient<>(left, trip.items().raw()), trip.start(), trip.arrive(), trip.returning()));
        return false;
    }

    /** Hands over every trip that has arrived. */
    public static void arrivals(MinecraftServer server) {
        DeliveryState state = DeliveryState.get(server);
        long now = now(server);
        for (DeliveryState.Trip trip : state.trips()) {
            if (trip.arrive() <= now) arrive(server, state, trip);
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        arrivals(server);
        DeliveryNetwork.pushStatus(server);
    }
}
