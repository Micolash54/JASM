package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.function.ToLongBiFunction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStackResourceHandler;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What machines, hoppers and pipes push into a port. Items wait in the port's holding buffer for its next operation.
 * Fluid is stored as the push goes through: first what a job on the port waits for, then what the network's storage has
 * room for, within the port's allowance, so none waits in the port. Every port that takes pushes uses this one; its
 * handlers are made once per face.
 *
 * <p>A Passive Access Port also holds its sets here for pipes to take: the items in hidden slots of the port, the fluid
 * in a hidden tank kept below. Pipes can take from those and nothing else, and can't put anything in them.
 */
final class PortIntake {
    /** Fluid a push handed over in the transaction still open, stored when it commits. */
    private record PushedFluid(@Nullable Direction side, FluidResource fluid, long amount) {}

    private record HeldFluid(FluidResource fluid, long amount) {
        static final Codec<HeldFluid> CODEC = RecordCodecBuilder.create(i -> i.group(
                FluidResource.CODEC.fieldOf("fluid").forGetter(HeldFluid::fluid),
                Codec.LONG.fieldOf("amount").forGetter(HeldFluid::amount))
                .apply(i, HeldFluid::new));
    }

    private final AccessPortBlockEntity port;
    private final List<PushedFluid> pushedFluids = new ArrayList<>();
    /** Handed over but found no room when stored (something else filled it within the same push); kept until it fits. */
    private final Map<FluidResource, Long> heldFluids = new LinkedHashMap<>();
    private final SnapshotJournal<List<PushedFluid>> pushJournal = new SnapshotJournal<>() {
        @Override
        protected List<PushedFluid> createSnapshot() {
            return List.copyOf(pushedFluids);
        }

        @Override
        protected void revertToSnapshot(List<PushedFluid> snapshot) {
            pushedFluids.clear();
            pushedFluids.addAll(snapshot);
        }

        @Override
        protected void onRootCommit(List<PushedFluid> originalState) {
            storePushedFluids();
        }
    };
    /** Slots in the hidden tank: one per fluid a set brings, and a card has at most nine inputs. */
    static final int OUTGOING_SLOTS = ProcessingCard.INPUTS;
    /** Each hidden tank slot holds as much as one input of a card may ask for. */
    static final long OUTGOING_CAPACITY = ProcessingCard.MAX_FLUID;
    private final FluidResource[] outgoingFluid = new FluidResource[OUTGOING_SLOTS];
    private final long[] outgoingAmount = new long[OUTGOING_SLOTS];
    private record Outgoing(FluidResource[] fluid, long[] amount) {}
    private final SnapshotJournal<Outgoing> outgoingJournal = new SnapshotJournal<>() {
        @Override
        protected Outgoing createSnapshot() {
            return new Outgoing(outgoingFluid.clone(), outgoingAmount.clone());
        }

        @Override
        protected void revertToSnapshot(Outgoing snapshot) {
            System.arraycopy(snapshot.fluid(), 0, outgoingFluid, 0, OUTGOING_SLOTS);
            System.arraycopy(snapshot.amount(), 0, outgoingAmount, 0, OUTGOING_SLOTS);
        }

        @Override
        protected void onRootCommit(Outgoing originalState) {
            port.setChanged();
        }
    };
    /** One per face, and the last for a lookup with no side. */
    private final ResourceHandler<ItemResource>[] itemInputs = newItemInputs();
    private final FluidInput[] fluidInputs = new FluidInput[7];

    PortIntake(AccessPortBlockEntity port) {
        this.port = port;
        java.util.Arrays.fill(outgoingFluid, FluidResource.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static ResourceHandler<ItemResource>[] newItemInputs() {
        return new ResourceHandler[7];
    }

    private static int index(@Nullable Direction side) {
        return side == null ? 6 : side.ordinal();
    }

    /**
     * Items pushed in through {@code side} go into the holding buffer, as far as the port's own rules let them. On a full
     * Access Port the handler also shows the hidden slots after them, which only a Passive port lets pipes take from.
     */
    ResourceHandler<ItemResource> itemInput(@Nullable Direction side) {
        int index = index(side);
        if (itemInputs[index] == null) {
            Direction face = side == null ? Direction.UP : side;
            itemInputs[index] = port.canBePassive() ? new FullPortItems(face) : new WorldlyContainerWrapper(port, face);
        }
        return itemInputs[index];
    }

    /** The full Access Port's items as pipes see them: the incoming slots, then the hidden ones. */
    private final class FullPortItems implements ResourceHandler<ItemResource> {
        private final Direction side;
        private final ResourceHandler<ItemResource> incoming;
        private final HeldSlot[] held = new HeldSlot[AccessPortBlockEntity.HELD_SLOTS];

        FullPortItems(Direction side) {
            this.side = side;
            this.incoming = VanillaContainerWrapper.of(port);
            for (int i = 0; i < held.length; i++) held[i] = new HeldSlot(AccessPortBlockEntity.HELD_START + i);
        }

        /** The hidden slots show only while Passive; an Active port looks as it always did. */
        @Override
        public int size() {
            return AccessPortBlockEntity.BUFFER_SLOTS + (port.passive() ? held.length : 0);
        }

        private boolean isHeld(int index) {
            // a pipe that counted the slots while Passive may still ask for a hidden one: it is empty then
            Objects.checkIndex(index, AccessPortBlockEntity.BUFFER_SLOTS + held.length);
            return index >= AccessPortBlockEntity.BUFFER_SLOTS;
        }

        @Override
        public ItemResource getResource(int index) {
            return isHeld(index) ? held[index - AccessPortBlockEntity.BUFFER_SLOTS].getResource(0) : incoming.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return isHeld(index) ? held[index - AccessPortBlockEntity.BUFFER_SLOTS].getAmountAsLong(0) : incoming.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return isHeld(index) ? held[index - AccessPortBlockEntity.BUFFER_SLOTS].getCapacityAsLong(0, resource)
                    : incoming.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return !isHeld(index) && incoming.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext tx) {
            if (isHeld(index) || !port.canPlaceItemThroughFace(index, resource.toStack(), side)) return 0;
            return incoming.insert(index, resource, amount, tx);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext tx) {
            if (!isHeld(index) || !port.passive()) return 0;
            return held[index - AccessPortBlockEntity.BUFFER_SLOTS].extract(0, resource, amount, tx);
        }
    }

    /** One hidden slot of a Passive port, taken from by pipes. */
    private final class HeldSlot extends ItemStackResourceHandler {
        private final int slot;

        HeldSlot(int slot) {
            this.slot = slot;
        }

        @Override
        protected ItemStack getStack() {
            return port.getItem(slot);
        }

        @Override
        protected void setStack(ItemStack stack) {
            port.getItems().set(slot, stack);
        }

        @Override
        protected boolean isValid(ItemResource resource) {
            return false;
        }

        @Override
        protected void onRootCommit(ItemStack originalState) {
            port.setChanged();
        }
    }

    // --- the hidden tank of a Passive port ---

    /** Whether the hidden tank holds nothing. */
    boolean outgoingEmpty() {
        for (long amount : outgoingAmount) if (amount > 0) return false;
        return true;
    }

    /** What the hidden tank holds, by fluid. */
    Map<FluidResource, Long> outgoing() {
        Map<FluidResource, Long> held = new LinkedHashMap<>();
        for (int i = 0; i < OUTGOING_SLOTS; i++) if (outgoingAmount[i] > 0) held.merge(outgoingFluid[i], outgoingAmount[i], Long::sum);
        return held;
    }

    /** Puts {@code fluids} into the hidden tank, all or nothing. With {@code simulate} it only says whether they fit. */
    boolean holdOutgoing(Map<FluidResource, Long> fluids, boolean simulate) {
        FluidResource[] fluid = outgoingFluid.clone();
        long[] amount = outgoingAmount.clone();
        for (Map.Entry<FluidResource, Long> wanted : fluids.entrySet()) {
            int slot = -1;
            for (int i = 0; i < OUTGOING_SLOTS && slot < 0; i++) if (amount[i] > 0 && fluid[i].equals(wanted.getKey())) slot = i;
            for (int i = 0; i < OUTGOING_SLOTS && slot < 0; i++) if (amount[i] <= 0) slot = i;
            if (slot < 0 || amount[slot] + wanted.getValue() > OUTGOING_CAPACITY) return false;
            fluid[slot] = wanted.getKey();
            amount[slot] += wanted.getValue();
        }
        if (!simulate) {
            System.arraycopy(fluid, 0, outgoingFluid, 0, OUTGOING_SLOTS);
            System.arraycopy(amount, 0, outgoingAmount, 0, OUTGOING_SLOTS);
            port.setChanged();
        }
        return true;
    }

    /** Hands the hidden tank to {@code taker}, which says how much of each it took; the rest stays. */
    void emptyOutgoing(ToLongBiFunction<FluidResource, Long> taker) {
        for (int i = 0; i < OUTGOING_SLOTS; i++) {
            if (outgoingAmount[i] <= 0) continue;
            long took = Math.clamp(taker.applyAsLong(outgoingFluid[i], outgoingAmount[i]), 0, outgoingAmount[i]);
            outgoingAmount[i] -= took;
            if (outgoingAmount[i] <= 0) {
                outgoingAmount[i] = 0;
                outgoingFluid[i] = FluidResource.EMPTY;
            }
        }
        port.setChanged();
    }

    /** Fluid pushed in through {@code side} (null when no side is given). Automation can't take any out. */
    ResourceHandler<FluidResource> fluidInput(@Nullable Direction side) {
        int index = index(side);
        if (fluidInputs[index] == null) fluidInputs[index] = new FluidInput(side);
        return fluidInputs[index];
    }

    boolean holdsFluid() {
        return !heldFluids.isEmpty();
    }

    /** How much of {@code fluid}, up to {@code amount}, a push through {@code side} may hand over now. Changes nothing. */
    private long fluidRoom(@Nullable Direction side, FluidResource fluid, long amount, TransactionContext tx) {
        if (!(port.getLevel() instanceof ServerLevel serverLevel) || port.stopped() || !port.acceptsPushedFluid(fluid)
                || port.takenElsewhere(side)) return 0;
        long accepted = 0;
        long same = heldFluids.getOrDefault(fluid, 0L);
        for (PushedFluid push : pushedFluids) {
            accepted += push.amount();
            if (push.fluid().equals(fluid)) same += push.amount();
        }
        // The allowance stops an unpowered, blocked or paused port too.
        long most = Math.min(amount, (long) port.pushBudget() * FluidAmounts.PER_SHARE - accepted);
        if (most <= 0) return 0;
        long wanted = same + most;
        long room = port.creditPushedFluid(serverLevel, side, fluid, wanted, true);
        AccessPortBlockEntity.DeckTarget target = room < wanted ? port.deckTarget(serverLevel) : null;
        if (target != null) {
            room += Math.min(storage(serverLevel, target, side).roomFluid(fluid, wanted - room, DeckStorage.Excess.VOID, tx),
                    DeckStorage.affordableFluid(target.deck()));
        }
        return Math.clamp(room - same, 0, most);
    }

    private DeckStorage.Checked storage(ServerLevel serverLevel, AccessPortBlockEntity.DeckTarget target, @Nullable Direction side) {
        var storage = DeckStorage.checked(WaferStore.get(serverLevel.getServer()), target.deck(), target.player());
        // never back into the machine it came from
        return side == null ? storage : storage.avoiding(NetworkNode.of(serverLevel, port.getBlockPos().relative(side)));
    }

    /** The push went through: what it handed over goes to waiting jobs first, then the network. */
    private void storePushedFluids() {
        List<PushedFluid> pushes = List.copyOf(pushedFluids);
        pushedFluids.clear();
        long total = pushes.stream().mapToLong(PushedFluid::amount).sum();
        if (total <= 0) return;
        port.pushed((int) FluidAmounts.shares(total));
        for (PushedFluid push : pushes) {
            long left = storeFluid(push.side(), push.fluid(), push.amount());
            // Only if something else filled the room within the same push: kept and stored once there is room.
            if (left > 0) heldFluids.merge(push.fluid(), left, Long::sum);
        }
        port.setChanged();
    }

    /** Stores {@code amount} of {@code fluid}: waiting jobs first, then the network. Returns what found no place. */
    private long storeFluid(@Nullable Direction side, FluidResource fluid, long amount) {
        if (!(port.getLevel() instanceof ServerLevel serverLevel)) return amount;
        long left = amount - port.creditPushedFluid(serverLevel, side, fluid, amount, false);
        AccessPortBlockEntity.DeckTarget target = left > 0 ? port.deckTarget(serverLevel) : null;
        if (target != null) {
            left -= storage(serverLevel, target, side).depositFluidResult(fluid, left, DeckStorage.Excess.VOID).total();
            Jobs.refreshOpenDeck(target.player(), target.deck());
        }
        return left;
    }

    /** Fluid a push handed over that found no room when it was stored, tried again once a second. */
    void storeHeldFluids() {
        if (heldFluids.isEmpty() || port.stopped() || port.networkBlocked() || port.redstonePaused()) return;
        for (FluidResource fluid : List.copyOf(heldFluids.keySet())) {
            Long amount = heldFluids.remove(fluid);
            long left = amount == null ? 0 : storeFluid(null, fluid, amount);
            if (left > 0) heldFluids.merge(fluid, left, Long::sum);
        }
        port.setChanged();
    }

    /** The port is going: fluid it still holds goes to the network if there is room. */
    void releaseHeldFluids() {
        for (FluidResource fluid : List.copyOf(heldFluids.keySet())) {
            Long amount = heldFluids.remove(fluid);
            long left = amount == null ? 0 : storeFluid(null, fluid, amount);
            if (left > 0) {
                Jasm.LOGGER.warn("Port at {} was removed holding {} mB of {} with no room for it on its network", port.getBlockPos(), left,
                        fluid.getFluid());
            }
        }
    }

    void save(ValueOutput output) {
        if (!heldFluids.isEmpty()) {
            output.store("held_fluids", HeldFluid.CODEC.listOf(),
                    heldFluids.entrySet().stream().map(e -> new HeldFluid(e.getKey(), e.getValue())).toList());
        }
        if (!outgoingEmpty()) {
            List<HeldFluid> out = new ArrayList<>();
            for (int i = 0; i < OUTGOING_SLOTS; i++) if (outgoingAmount[i] > 0) out.add(new HeldFluid(outgoingFluid[i], outgoingAmount[i]));
            output.store("outgoing_fluids", HeldFluid.CODEC.listOf(), out);
        }
    }

    void load(ValueInput input) {
        heldFluids.clear();
        input.read("held_fluids", HeldFluid.CODEC.listOf()).orElse(List.of())
                .forEach(held -> heldFluids.merge(held.fluid(), held.amount(), Long::sum));
        java.util.Arrays.fill(outgoingFluid, FluidResource.EMPTY);
        java.util.Arrays.fill(outgoingAmount, 0);
        List<HeldFluid> out = input.read("outgoing_fluids", HeldFluid.CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < Math.min(out.size(), OUTGOING_SLOTS); i++) {
            outgoingFluid[i] = out.get(i).fluid();
            outgoingAmount[i] = out.get(i).amount();
        }
    }

    /**
     * One face's fluid input: one empty tank that never fills, since what comes in is stored at once. On a full Access Port
     * the hidden tank's slots follow it; only a Passive port lets pipes drain them, and nothing can be put in them.
     */
    private final class FluidInput implements ResourceHandler<FluidResource> {
        private final @Nullable Direction side;

        FluidInput(@Nullable Direction side) {
            this.side = side;
        }

        /** The hidden tank shows only while Passive; an Active port looks as it always did. */
        @Override
        public int size() {
            return port.passive() ? 1 + OUTGOING_SLOTS : 1;
        }

        /** Every index the handler answers for: a pipe that counted the tank while Passive may still ask for it. */
        private int range() {
            return port.canBePassive() ? 1 + OUTGOING_SLOTS : 1;
        }

        @Override
        public FluidResource getResource(int index) {
            Objects.checkIndex(index, range());
            return index == 0 ? FluidResource.EMPTY : outgoingFluid[index - 1];
        }

        @Override
        public long getAmountAsLong(int index) {
            Objects.checkIndex(index, range());
            return index == 0 ? 0 : outgoingAmount[index - 1];
        }

        /** The most one step of the port can take; a hidden tank slot's own size. */
        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            Objects.checkIndex(index, range());
            return index == 0 ? (long) port.transferRate() * FluidAmounts.PER_SHARE : OUTGOING_CAPACITY;
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            Objects.checkIndex(index, range());
            return index == 0 && !resource.isEmpty() && port.acceptsPushedFluid(resource);
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext tx) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            Objects.checkIndex(index, range());
            if (index != 0) return 0;
            long accepted = fluidRoom(side, resource, amount, tx);
            if (accepted <= 0) return 0;
            pushJournal.updateSnapshots(tx);
            pushedFluids.add(new PushedFluid(side, resource, accepted));
            return (int) accepted;
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext tx) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            Objects.checkIndex(index, range());
            int slot = index - 1;
            if (index == 0 || !port.passive() || outgoingAmount[slot] <= 0 || !outgoingFluid[slot].equals(resource)) return 0;
            int taken = (int) Math.min(amount, outgoingAmount[slot]);
            outgoingJournal.updateSnapshots(tx);
            outgoingAmount[slot] -= taken;
            if (outgoingAmount[slot] <= 0) {
                outgoingAmount[slot] = 0;
                outgoingFluid[slot] = FluidResource.EMPTY;
            }
            return taken;
        }
    }
}
