package dev.micolash.jasm.delivery;

import dev.micolash.jasm.Jasm;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Messages between the Deck to Deck window and the server. */
public final class DeliveryPayloads {
    /** Most trips one status lists. */
    public static final int MAX_TRIPS = 32;
    /** Most players one list shows. */
    public static final int MAX_PEOPLE = 64;

    private DeliveryPayloads() {}

    /** A trip as the window shows it: who it's with, its first item, how many in all, and how far along it is. */
    public record TripView(UUID id, boolean outgoing, boolean returning, String other, ItemStack icon, int count, int left, int total) {
        static final StreamCodec<RegistryFriendlyByteBuf, TripView> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, TripView::id,
                ByteBufCodecs.BOOL, TripView::outgoing,
                ByteBufCodecs.BOOL, TripView::returning,
                ByteBufCodecs.stringUtf8(64), TripView::other,
                ItemStack.OPTIONAL_STREAM_CODEC, TripView::icon,
                ByteBufCodecs.VAR_INT, TripView::count,
                ByteBufCodecs.VAR_INT, TripView::left,
                ByteBufCodecs.VAR_INT, TripView::total,
                TripView::new);

        /** Here, but the inbox has no room for it yet. */
        public boolean waiting() {
            return left <= 0;
        }
    }

    /** Someone the Deck could send to, how long it would take, and what stops it (if anything). */
    public record Person(UUID id, String name, int ticks, int refusal) {
        static final StreamCodec<RegistryFriendlyByteBuf, Person> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Person::id,
                ByteBufCodecs.stringUtf8(64), Person::name,
                ByteBufCodecs.VAR_INT, Person::ticks,
                ByteBufCodecs.VAR_INT, Person::refusal,
                Person::new);

        public Deliveries.Refusal reason() {
            return Deliveries.Refusal.byId(refusal);
        }
    }

    /** Client → server: the window opened or shut, so status starts or stops. */
    public record Watch(int containerId, boolean on) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(Jasm.id("send_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Watch::containerId,
                ByteBufCodecs.BOOL, Watch::on,
                Watch::new);

        @Override
        public Type<Watch> type() { return TYPE; }
    }

    /** Client → server: who can this Deck send to? */
    public record AskPeople(int containerId) implements CustomPacketPayload {
        public static final Type<AskPeople> TYPE = new Type<>(Jasm.id("send_ask_people"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AskPeople> STREAM_CODEC = ByteBufCodecs.VAR_INT
                .<RegistryFriendlyByteBuf>cast().map(AskPeople::new, AskPeople::containerId);

        @Override
        public Type<AskPeople> type() { return TYPE; }
    }

    /** Client → server: send the grid to this player. */
    public record Send(int containerId, UUID to) implements CustomPacketPayload {
        public static final Type<Send> TYPE = new Type<>(Jasm.id("send_items"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Send> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Send::containerId,
                UUIDUtil.STREAM_CODEC, Send::to,
                Send::new);

        @Override
        public Type<Send> type() { return TYPE; }
    }

    /** Client → server: call back a trip. */
    public record Cancel(int containerId, UUID trip) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = new Type<>(Jasm.id("send_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Cancel::containerId,
                UUIDUtil.STREAM_CODEC, Cancel::trip,
                Cancel::new);

        @Override
        public Type<Cancel> type() { return TYPE; }
    }

    /** Client → server: move everything in the inbox onto the Deck. */
    public record StoreAll(int containerId) implements CustomPacketPayload {
        public static final Type<StoreAll> TYPE = new Type<>(Jasm.id("send_store_all"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreAll> STREAM_CODEC = ByteBufCodecs.VAR_INT
                .<RegistryFriendlyByteBuf>cast().map(StoreAll::new, StoreAll::containerId);

        @Override
        public Type<StoreAll> type() { return TYPE; }
    }

    /** Server → client: this player's trips, both ways. */
    public record Status(int containerId, List<TripView> trips) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(Jasm.id("send_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Status::containerId,
                TripView.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TRIPS)), Status::trips,
                Status::new);

        @Override
        public Type<Status> type() { return TYPE; }
    }

    /** Server → client: the people this Deck can send to; {@code refusal} says why when there's nobody to pick. */
    public record People(int containerId, List<Person> people, int refusal) implements CustomPacketPayload {
        public static final Type<People> TYPE = new Type<>(Jasm.id("send_people"));
        public static final StreamCodec<RegistryFriendlyByteBuf, People> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, People::containerId,
                Person.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PEOPLE)), People::people,
                ByteBufCodecs.VAR_INT, People::refusal,
                People::new);

        @Override
        public Type<People> type() { return TYPE; }
    }
}
