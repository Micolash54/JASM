package dev.micolash.jasm.delivery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.LenientListCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Deck to Deck: every trip on its way and every player's inbox. Items whose mod is gone are kept as they were saved
 * and written back unchanged, so a removed mod never costs anyone their items.
 */
public final class DeliveryState extends SavedData {
    /** Stacks an inbox holds before arriving trips wait for room. */
    public static final int INBOX = 27;

    private static final LenientListCodec<ItemStack> ITEMS = new LenientListCodec<>(ItemStack.CODEC, "delivered item");

    /**
     * Items on their way from one player to another. A cancelled trip turns round: it goes back to its sender, who is
     * then both {@code from} and {@code to}, and it arrives at once.
     */
    public record Trip(UUID id, UUID from, String fromName, UUID to, String toName, LenientListCodec.Lenient<ItemStack> items,
            long start, long arrive, boolean returning) {
        static final Codec<Trip> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Trip::id),
                UUIDUtil.CODEC.fieldOf("from").forGetter(Trip::from),
                Codec.STRING.optionalFieldOf("from_name", "").forGetter(Trip::fromName),
                UUIDUtil.CODEC.fieldOf("to").forGetter(Trip::to),
                Codec.STRING.optionalFieldOf("to_name", "").forGetter(Trip::toName),
                ITEMS.fieldOf("items").forGetter(Trip::items),
                Codec.LONG.fieldOf("start").forGetter(Trip::start),
                Codec.LONG.fieldOf("arrive").forGetter(Trip::arrive),
                Codec.BOOL.optionalFieldOf("returning", false).forGetter(Trip::returning))
                .apply(i, Trip::new));

        public int count() {
            return items.values().stream().mapToInt(ItemStack::getCount).sum();
        }

        Trip turnedRound(long now) {
            return new Trip(id, from, fromName, from, fromName, items, now, now, true);
        }
    }

    private record Inbox(UUID player, LenientListCodec.Lenient<ItemStack> items) {
        static final Codec<Inbox> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Inbox::player),
                ITEMS.fieldOf("items").forGetter(Inbox::items))
                .apply(i, Inbox::new));
    }

    private static final LenientListCodec<Trip> TRIPS = new LenientListCodec<>(Trip.CODEC, "delivery trip");

    public static final Codec<DeliveryState> CODEC = RecordCodecBuilder.create(i -> i.group(
            TRIPS.optionalFieldOf("trips", LenientListCodec.Lenient.of(List.of()))
                    .forGetter(s -> new LenientListCodec.Lenient<>(List.copyOf(s.trips.values()), s.rawTrips)),
            Inbox.CODEC.listOf().optionalFieldOf("inboxes", List.of()).forGetter(DeliveryState::inboxesForSave))
            .apply(i, DeliveryState::new));

    public static final SavedDataType<DeliveryState> TYPE = new SavedDataType<>(Jasm.id("deck_deliveries"), DeliveryState::new, CODEC);

    private final Map<UUID, Trip> trips = new LinkedHashMap<>();
    private final List<Dynamic<?>> rawTrips;
    /** Each player's inbox: stacks in order, no empty ones once tidied. */
    private final Map<UUID, List<ItemStack>> inboxes = new LinkedHashMap<>();
    /** Inbox items that can't be read right now (their mod is missing), kept for when it comes back. */
    private final Map<UUID, List<Dynamic<?>>> rawInboxes = new LinkedHashMap<>();

    public DeliveryState() {
        this.rawTrips = new ArrayList<>();
    }

    private DeliveryState(LenientListCodec.Lenient<Trip> trips, List<Inbox> inboxes) {
        trips.values().forEach(t -> this.trips.put(t.id(), t));
        this.rawTrips = new ArrayList<>(trips.raw());
        for (Inbox inbox : inboxes) {
            List<ItemStack> items = new ArrayList<>(inbox.items().values().stream().filter(s -> !s.isEmpty()).toList());
            if (!items.isEmpty()) this.inboxes.computeIfAbsent(inbox.player(), k -> new ArrayList<>()).addAll(items);
            if (!inbox.items().raw().isEmpty()) this.rawInboxes.computeIfAbsent(inbox.player(), k -> new ArrayList<>()).addAll(inbox.items().raw());
        }
    }

    public static DeliveryState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private List<Inbox> inboxesForSave() {
        List<Inbox> out = new ArrayList<>();
        List<UUID> players = new ArrayList<>(inboxes.keySet());
        rawInboxes.keySet().stream().filter(p -> !players.contains(p)).forEach(players::add);
        for (UUID player : players) {
            List<ItemStack> items = inboxes.getOrDefault(player, List.of()).stream().filter(s -> !s.isEmpty()).toList();
            out.add(new Inbox(player, new LenientListCodec.Lenient<>(items, rawInboxes.getOrDefault(player, List.of()))));
        }
        return out;
    }

    // --- trips ---

    public List<Trip> trips() {
        return List.copyOf(trips.values());
    }

    public Trip trip(UUID id) {
        return trips.get(id);
    }

    public void addTrip(Trip trip) {
        trips.put(trip.id(), trip);
        setDirty();
    }

    void replaceTrip(Trip trip) {
        trips.put(trip.id(), trip);
        setDirty();
    }

    void removeTrip(UUID id) {
        if (trips.remove(id) != null) setDirty();
    }

    /** Trips {@code player} sent that are still going out (not turned round). */
    public int outgoing(UUID player) {
        int count = 0;
        for (Trip trip : trips.values()) if (trip.from().equals(player) && !trip.returning()) count++;
        return count;
    }

    // --- inboxes ---

    /** The live inbox of {@code player}; changes to it must be followed by {@link #inboxChanged}. */
    public List<ItemStack> inbox(UUID player) {
        return inboxes.computeIfAbsent(player, k -> new ArrayList<>());
    }

    /** Drops emptied stacks and forgets an inbox with nothing left in it. */
    public void inboxChanged(UUID player) {
        List<ItemStack> items = inboxes.get(player);
        if (items != null) {
            items.removeIf(ItemStack::isEmpty);
            if (items.isEmpty()) inboxes.remove(player);
        }
        setDirty();
    }

    public boolean hasInbox(UUID player) {
        List<ItemStack> items = inboxes.get(player);
        return items != null && items.stream().anyMatch(s -> !s.isEmpty());
    }

    /** Puts a whole trip's items in {@code player}'s inbox if they all fit; otherwise nothing changes. */
    boolean deliver(UUID player, LenientListCodec.Lenient<ItemStack> items) {
        List<ItemStack> merged = merge(inboxes.getOrDefault(player, List.of()), items.values());
        if (merged.size() > INBOX) return false;
        inboxes.put(player, merged);
        if (!items.raw().isEmpty()) rawInboxes.computeIfAbsent(player, k -> new ArrayList<>()).addAll(items.raw());
        if (merged.isEmpty()) inboxes.remove(player);
        setDirty();
        return true;
    }

    /** {@code inbox} with {@code items} added: topping up matching stacks first, then new stacks at the end. */
    static List<ItemStack> merge(List<ItemStack> inbox, List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : inbox) if (!stack.isEmpty()) out.add(stack.copy());
        for (ItemStack incoming : items) {
            ItemStack left = incoming.copy();
            for (ItemStack there : out) {
                if (left.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(there, left) && there.getCount() < there.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), there.getMaxStackSize() - there.getCount());
                    there.grow(move);
                    left.shrink(move);
                }
            }
            while (!left.isEmpty()) out.add(left.split(left.getMaxStackSize()));
        }
        return out;
    }
}
