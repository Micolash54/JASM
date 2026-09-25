package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Messages between the Deck screen and the server. */
public final class DeckPayloads {
    private DeckPayloads() {}

    /** One kind of item and how many the open Deck holds. */
    public record Entry(ItemResource key, long count) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ItemResource.STREAM_CODEC, Entry::key,
                ByteBufCodecs.VAR_LONG, Entry::count,
                Entry::new);
        static final StreamCodec<RegistryFriendlyByteBuf, List<Entry>> LIST = STREAM_CODEC.apply(ByteBufCodecs.list());
    }

    public enum ExtractMode {
        /** Up to one full stack onto the cursor (left click). */
        STACK,
        /** Half of what one stack would be onto the cursor (right click). */
        HALF,
        /** One stack straight into the inventory (shift-click). */
        TO_INVENTORY;

        static final StreamCodec<ByteBuf, ExtractMode> STREAM_CODEC =
                ByteBufCodecs.idMapper(i -> values()[Math.floorMod(i, values().length)], ExtractMode::ordinal);
    }

    /** Client → server: take an item out of the Deck. */
    public record Extract(int containerId, ItemResource key, ExtractMode mode) implements CustomPacketPayload {
        public static final Type<Extract> TYPE = new Type<>(Jasm.id("deck_extract"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Extract> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Extract::containerId,
                ItemResource.STREAM_CODEC, Extract::key,
                ExtractMode.STREAM_CODEC, Extract::mode,
                Extract::new);

        @Override
        public Type<Extract> type() {
            return TYPE;
        }
    }

    /** Client → server: store what the cursor holds (all of it, or one). */
    public record Insert(int containerId, boolean one) implements CustomPacketPayload {
        public static final Type<Insert> TYPE = new Type<>(Jasm.id("deck_insert"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Insert> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Insert::containerId,
                ByteBufCodecs.BOOL, Insert::one,
                Insert::new);

        @Override
        public Type<Insert> type() {
            return TYPE;
        }
    }

    /** Server → client: part of the full contents. Page 0 starts over. */
    public record Snapshot(int containerId, int page, int pages, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Jasm.id("deck_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Snapshot::containerId,
                ByteBufCodecs.VAR_INT, Snapshot::page,
                ByteBufCodecs.VAR_INT, Snapshot::pages,
                Entry.LIST, Snapshot::entries,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }

    /** Server → client: counts that changed; 0 means gone. */
    public record Delta(int containerId, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Delta> TYPE = new Type<>(Jasm.id("deck_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Delta> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Delta::containerId,
                Entry.LIST, Delta::entries,
                Delta::new);

        @Override
        public Type<Delta> type() {
            return TYPE;
        }
    }

    /** Server → client: charge and the state of each wafer slot. */
    public record Status(int containerId, int energy, List<DeckStorage.SlotStatus> slots) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(Jasm.id("deck_status"));
        static final StreamCodec<RegistryFriendlyByteBuf, DeckStorage.SlotStatus> SLOT = StreamCodec.composite(
                ByteBufCodecs.BOOL, DeckStorage.SlotStatus::present,
                ByteBufCodecs.VAR_LONG, DeckStorage.SlotStatus::used,
                ByteBufCodecs.VAR_LONG, DeckStorage.SlotStatus::capacity,
                ByteBufCodecs.VAR_LONG, DeckStorage.SlotStatus::fromMissingMods,
                ByteBufCodecs.BOOL, DeckStorage.SlotStatus::linked,
                ByteBufCodecs.VAR_LONG, DeckStorage.SlotStatus::typesUsed,
                ByteBufCodecs.VAR_INT, DeckStorage.SlotStatus::types,
                DeckStorage.SlotStatus::new);
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Status::containerId,
                ByteBufCodecs.VAR_INT, Status::energy,
                SLOT.apply(ByteBufCodecs.list()), Status::slots,
                Status::new);

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }
}
