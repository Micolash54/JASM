package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.WaferSettings;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Messages between the Deck screen and the server. */
public final class DeckPayloads {
    private DeckPayloads() {}

    /** One kind of item and how many the open Deck holds. */
    public record Entry(ItemResource key, long count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
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

        static final StreamCodec<ByteBuf, ExtractMode> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[Math.floorMod(i, values().length)],
                ExtractMode::ordinal);
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

    /** Client → server: empty the Crafting Deck's grid onto its wafers, or into the player's inventory. */
    public record ClearGrid(int containerId, boolean toInventory) implements CustomPacketPayload {
        public static final Type<ClearGrid> TYPE = new Type<>(Jasm.id("deck_clear_grid"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClearGrid> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ClearGrid::containerId,
                ByteBufCodecs.BOOL, ClearGrid::toInventory,
                ClearGrid::new);

        @Override
        public Type<ClearGrid> type() {
            return TYPE;
        }
    }

    /** Most items one grid slot may list as allowed. */
    public static final int MAX_OPTIONS = 256;

    /**
     * Client → server: fill the Crafting Deck's grid for a recipe. For each of the 9 slots, the items it may hold;
     * {@code max} fills as many sets as fit.
     */
    public record FillGrid(int containerId, List<List<ItemResource>> slots, boolean max) implements CustomPacketPayload {
        public static final Type<FillGrid> TYPE = new Type<>(Jasm.id("deck_fill_grid"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FillGrid> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FillGrid::containerId,
                ItemResource.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OPTIONS)).apply(ByteBufCodecs.list(9)), FillGrid::slots,
                ByteBufCodecs.BOOL, FillGrid::max,
                FillGrid::new);

        @Override
        public Type<FillGrid> type() {
            return TYPE;
        }
    }

    /** The screen changed one wafer's routing settings. */
    public record Configure(int containerId, int slot, WaferSettings settings) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(Jasm.id("deck_configure"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Configure> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Configure::containerId,
                ByteBufCodecs.VAR_INT, Configure::slot,
                WaferSettings.STREAM_CODEC, Configure::settings,
                Configure::new);

        @Override
        public Type<Configure> type() {
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
                WaferSettings.STREAM_CODEC, DeckStorage.SlotStatus::settings,
                ByteBufCodecs.BOOL, DeckStorage.SlotStatus::fluid,
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

    /** One kind of fluid and how many millibuckets the open Deck holds. */
    public record FluidEntry(FluidResource key, long amount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FluidEntry> STREAM_CODEC = StreamCodec.composite(
                FluidResource.STREAM_CODEC, FluidEntry::key,
                ByteBufCodecs.VAR_LONG, FluidEntry::amount,
                FluidEntry::new);
        static final StreamCodec<RegistryFriendlyByteBuf, List<FluidEntry>> LIST = STREAM_CODEC.apply(ByteBufCodecs.list());
    }

    /** Server → client: part of the full fluid contents. Page 0 starts over. */
    public record FluidSnapshot(int containerId, int page, int pages, List<FluidEntry> entries) implements CustomPacketPayload {
        public static final Type<FluidSnapshot> TYPE = new Type<>(Jasm.id("deck_fluid_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FluidSnapshot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FluidSnapshot::containerId,
                ByteBufCodecs.VAR_INT, FluidSnapshot::page,
                ByteBufCodecs.VAR_INT, FluidSnapshot::pages,
                FluidEntry.LIST, FluidSnapshot::entries,
                FluidSnapshot::new);

        @Override
        public Type<FluidSnapshot> type() {
            return TYPE;
        }
    }

    /** Server → client: fluid amounts that changed; 0 means gone. */
    public record FluidDelta(int containerId, List<FluidEntry> entries) implements CustomPacketPayload {
        public static final Type<FluidDelta> TYPE = new Type<>(Jasm.id("deck_fluid_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FluidDelta> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FluidDelta::containerId,
                FluidEntry.LIST, FluidDelta::entries,
                FluidDelta::new);

        @Override
        public Type<FluidDelta> type() {
            return TYPE;
        }
    }

    /** What a click does with the container on the cursor and a fluid in the grid. */
    public enum FluidMode {
        /** Fill the container with a bucket's worth. */
        FILL,
        /** Fill the container completely. */
        FILL_ALL,
        /** Fill with a bucket's worth; the filled container goes to the inventory. */
        FILL_TO_INVENTORY,
        /** Fill completely; the filled container goes to the inventory. */
        FILL_ALL_TO_INVENTORY,
        /** Pour a bucket's worth of the container into the Deck (no fluid needed in the message). */
        EMPTY,
        /** Pour everything the container holds into the Deck. */
        EMPTY_ALL;

        static final StreamCodec<ByteBuf, FluidMode> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[Math.floorMod(i, values().length)],
                FluidMode::ordinal);

        public boolean fills() {
            return this == FILL || this == FILL_ALL || this == FILL_TO_INVENTORY || this == FILL_ALL_TO_INVENTORY;
        }
    }

    /** Client → server: fill or empty the cursor container. The fluid is only used when filling. */
    /** Shift-right-click on a container in the player's inventory: pour it into the Deck. {@code slot} is the menu slot. */
    public record PourSlot(int containerId, int slot) implements CustomPacketPayload {
        public static final Type<PourSlot> TYPE = new Type<>(Jasm.id("deck_pour_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PourSlot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PourSlot::containerId,
                ByteBufCodecs.VAR_INT, PourSlot::slot,
                PourSlot::new);

        @Override
        public Type<PourSlot> type() {
            return TYPE;
        }
    }

    public record FluidAction(int containerId, FluidResource key, FluidMode mode) implements CustomPacketPayload {
        public static final Type<FluidAction> TYPE = new Type<>(Jasm.id("deck_fluid_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FluidAction> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FluidAction::containerId,
                FluidResource.STREAM_CODEC, FluidAction::key,
                FluidMode.STREAM_CODEC, FluidAction::mode,
                FluidAction::new);

        @Override
        public Type<FluidAction> type() {
            return TYPE;
        }
    }
}
