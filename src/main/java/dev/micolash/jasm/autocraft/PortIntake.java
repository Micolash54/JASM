package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
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
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What machines, hoppers and pipes push into a port. Items wait in the port's holding buffer for its next operation.
 * Fluid is stored as the push goes through: first what a job on the port waits for, then what the network's storage has
 * room for, within the port's allowance, so none waits in the port. Every port that takes pushes uses this one; its
 * handlers are made once per face.
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
    /** One per face, and the last for a lookup with no side. */
    private final WorldlyContainerWrapper[] itemInputs = new WorldlyContainerWrapper[7];
    private final FluidInput[] fluidInputs = new FluidInput[7];

    PortIntake(AccessPortBlockEntity port) {
        this.port = port;
    }

    private static int index(@Nullable Direction side) {
        return side == null ? 6 : side.ordinal();
    }

    /** Items pushed in through {@code side} go into the holding buffer, as far as the port's own rules let them. */
    ResourceHandler<ItemResource> itemInput(@Nullable Direction side) {
        int index = index(side);
        if (itemInputs[index] == null) itemInputs[index] = new WorldlyContainerWrapper(port, side == null ? Direction.UP : side);
        return itemInputs[index];
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
        if (!(port.getLevel() instanceof ServerLevel serverLevel) || port.stopped() || !port.acceptsPushedFluid(fluid)) return 0;
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
        return side == null ? storage : storage.avoiding(port.getBlockPos().relative(side));
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
        if (heldFluids.isEmpty() || port.stopped() || !port.running() || port.networkBlocked() || port.redstonePaused()) return;
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
    }

    void load(ValueInput input) {
        heldFluids.clear();
        input.read("held_fluids", HeldFluid.CODEC.listOf()).orElse(List.of())
                .forEach(held -> heldFluids.merge(held.fluid(), held.amount(), Long::sum));
    }

    /** One face's fluid input: one empty tank that never fills, since what comes in is stored at once. */
    private final class FluidInput implements ResourceHandler<FluidResource> {
        private final @Nullable Direction side;

        FluidInput(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public FluidResource getResource(int index) {
            Objects.checkIndex(index, 1);
            return FluidResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            Objects.checkIndex(index, 1);
            return 0;
        }

        /** The most one step of the port can take. */
        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            Objects.checkIndex(index, 1);
            return (long) port.transferRate() * FluidAmounts.PER_SHARE;
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            Objects.checkIndex(index, 1);
            return !resource.isEmpty() && port.acceptsPushedFluid(resource);
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext tx) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            Objects.checkIndex(index, 1);
            long accepted = fluidRoom(side, resource, amount, tx);
            if (accepted <= 0) return 0;
            pushJournal.updateSnapshots(tx);
            pushedFluids.add(new PushedFluid(side, resource, accepted));
            return (int) accepted;
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext tx) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            Objects.checkIndex(index, 1);
            return 0;
        }
    }
}
