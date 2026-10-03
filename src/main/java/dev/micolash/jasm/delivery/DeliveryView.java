package dev.micolash.jasm.delivery;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Client side: what the server last said about this player's trips and who they can send to. */
public final class DeliveryView {
    private List<DeliveryPayloads.TripView> trips = List.of();
    /** Client game time when {@link #trips} came, so the bars keep moving between updates. */
    private long tripsAt;
    private DeliveryPayloads.@Nullable People people;

    public void applyStatus(List<DeliveryPayloads.TripView> trips, long now) {
        this.trips = List.copyOf(trips);
        this.tripsAt = now;
    }

    public List<DeliveryPayloads.TripView> trips() {
        return trips;
    }

    /** Ticks left on {@code trip} at client time {@code now}. */
    public int left(DeliveryPayloads.TripView trip, long now) {
        return (int) Math.max(0, trip.left() - (now - tripsAt));
    }

    public void applyPeople(DeliveryPayloads.People people) {
        this.people = people;
    }

    /** The last list of people, or null while it is on its way. */
    public DeliveryPayloads.@Nullable People people() {
        return people;
    }

    public void clearPeople() {
        people = null;
    }
}
