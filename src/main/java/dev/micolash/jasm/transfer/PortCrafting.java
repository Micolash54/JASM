package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.Card;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.autocraft.MaterialMarkerItem;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Crafting Upgrade: the port asks its Crafting Deck to craft what its Output filter sends, one job at a time, the
 * same way a Deck rule does. With a Stock number it crafts what the attached blocks and the system lack together;
 * without one it keeps crafting a stack at a time for as long as the ingredients last.
 */
final class PortCrafting {
    /** A fluid row without a Stock number crafts this many buckets per job. */
    private static final int FLUID_BUCKETS = 16;
    private long retryAt;
    private @Nullable UUID id;

    boolean due(long now) { return now >= retryAt; }

    void run(TransferPortBlockEntity port, ServerLevel world, CableNetwork network, ServerPlayer player, ItemStack deck,
            DeckStorage.Checked storage) {
        UUID id = id(port, world);
        if (busy(world, id)) return;
        WaferSettings output = port.filters().output();
        List<ItemStack> results = null;
        for (int row = 0; row < output.rules().size(); row++) {
            WaferSettings.Filter rule = output.rules().get(row);
            if (!rule.enabled() || !rule.allow()) continue;
            if (results == null) results = results(network, player);
            ItemStack target = target(output, row, results);
            if (target == null) continue;
            boolean fluid = FluidMarkerItem.isMarker(target);
            long amount;
            if (port.hasStockUpgrade() && rule.stock() > 0) {
                long missing = lacking(port, world, row, fluid) - inSystem(storage, rule, fluid);
                if (missing <= 0) continue;
                amount = fluid ? Math.ceilDiv(missing, FluidAmounts.PER_BUCKET) : missing;
            } else {
                amount = fluid ? FLUID_BUCKETS : target.getMaxStackSize();
            }
            if (start(player, deck, ItemResource.of(target), Math.min(amount, JasmConfig.MAX_REQUEST.getAsInt()), id)) {
                retryAt = 0;
                return;
            }
        }
        retryAt = world.getGameTime() + Tuning.RULE_RETRY_SECONDS * 20L;
    }

    /** Asks for {@code amount}, then for less while the ingredients or the server's memory fall short. */
    private static boolean start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, UUID id) {
        for (long ask = amount; ask >= 1; ask /= 2) {
            String problem = Jobs.start(player, deck, target, ask, null, id, false);
            if (problem == null) return true;
            if (!"message.jasm.craft.missing".equals(problem) && !"message.jasm.craft.too_big".equals(problem)) return false;
        }
        return false;
    }

    /** What the network's recipe cards make, in card order. Materials can't be asked for. */
    private static List<ItemStack> results(CableNetwork network, ServerPlayer player) {
        return Jobs.cards(network, player).stream().map(Card::result).filter(result -> !MaterialMarkerItem.isMarker(result)).toList();
    }

    /** The first craftable thing the Output filter sends through row {@code row}. */
    private static @Nullable ItemStack target(WaferSettings output, int row, List<ItemStack> results) {
        for (ItemStack result : results) {
            FluidResource fluid = FluidMarkerItem.fluidOf(result);
            int rank = fluid != null ? output.rank(fluid.getFluid()) : output.rank(result.getItem());
            if (rank == row) return result;
        }
        return null;
    }

    /** How much row {@code row} lacks in every block the port feeds, in items or mB. */
    private static long lacking(TransferPortBlockEntity port, ServerLevel world, int row, boolean fluid) {
        WaferSettings output = port.filters().output();
        long lacking = 0;
        for (Direction side : port.workFaces()) {
            BlockPos target = port.getBlockPos().relative(side);
            long left;
            if (fluid) {
                var tank = Machines.fluidInlet(world, target, side.getOpposite());
                if (tank == null) continue;
                left = PortStock.of(PortStock.fluidsLeft(output, tank), row);
            } else {
                var inventory = Machines.inlet(world, target, side.getOpposite());
                if (inventory == null) continue;
                left = PortStock.of(PortStock.itemsLeft(output, inventory), row);
            }
            lacking += left;
        }
        return lacking;
    }

    /** What the system already holds of everything the row matches. */
    private static long inSystem(DeckStorage.Checked storage, WaferSettings.Filter rule, boolean fluid) {
        long held = 0;
        if (fluid) {
            for (var entry : storage.fluidContents().entrySet()) if (rule.matches(entry.getKey().getFluid())) held += entry.getValue();
        } else {
            for (var entry : storage.contents().entrySet()) if (rule.matches(entry.getKey().getItem())) held += entry.getValue();
        }
        return held;
    }

    /** Whether this port's last job is still going. */
    private static boolean busy(ServerLevel world, UUID id) {
        for (AutocraftState.Job job : AutocraftState.get(world.getServer()).jobs()) {
            if (!job.finished() && job.rule().map(id::equals).orElse(false)) return true;
        }
        return false;
    }

    /** The name the port's jobs go by. It stays the same across restarts, so a job started before one still counts. */
    private UUID id(TransferPortBlockEntity port, ServerLevel world) {
        if (id == null) {
            String side = port.full() ? "full" : port.workFaces().getFirst().getSerializedName();
            id = UUID.nameUUIDFromBytes(("jasm:port/" + world.dimension().identifier() + "/" + port.getBlockPos().asLong() + "/" + side)
                    .getBytes(StandardCharsets.UTF_8));
        }
        return id;
    }
}
