package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.Card;
import dev.micolash.jasm.autocraft.CardRecipes;
import dev.micolash.jasm.autocraft.CraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.autocraft.MaterialMarkerItem;
import dev.micolash.jasm.autocraft.ProcessingCard;
import dev.micolash.jasm.autocraft.RecipeCard;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The Crafting Upgrade: the port asks its Crafting Deck to craft what its Output filter sends, one job at a time, the
 * same way a Deck rule does. With a Stock number it crafts what the attached blocks and the system lack together;
 * without one it crafts a stack at a time while the attached blocks have room for the whole result.
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
            if (results == null) results = results(world, network, player);
            ItemStack target = target(output, row, results);
            if (target == null) continue;
            boolean fluid = FluidMarkerItem.isMarker(target);
            long pending = pending(world, deck, rule, fluid);
            long held = inSystem(storage, rule, fluid);
            long room = Math.max(0, Math.max(0, room(port, world, row, target) - held) - pending);
            if (room <= 0) continue;
            long needed = Long.MAX_VALUE;
            long amount;
            if (port.hasStockUpgrade() && rule.stock() > 0) {
                needed = Math.max(0, Math.max(0, lacking(port, world, row, fluid) - held) - pending);
                if (needed <= 0) continue;
                amount = fluid ? Math.ceilDiv(Math.min(needed, room), FluidAmounts.PER_BUCKET) : Math.min(needed, room);
            } else {
                amount = fluid ? Math.min(FLUID_BUCKETS, Math.ceilDiv(room, FluidAmounts.PER_BUCKET))
                        : Math.min(Math.max(target.getMaxStackSize(), target.getCount()), room);
            }
            if (start(player, deck, ItemResource.of(target), Math.min(amount, JasmConfig.MAX_REQUEST.getAsInt()), id,
                    target.getCount(), storage, room, needed, pending)) {
                retryAt = 0;
                return;
            }
        }
        retryAt = world.getGameTime() + Tuning.RULE_RETRY_SECONDS * 20L;
    }

    /** Whole recipe batches must fit; Stock may keep the last batch's surplus on the Deck. */
    private static boolean start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, UUID id,
            int batch, DeckStorage.Checked storage, long room, long needed, long pending) {
        FluidResource fluid = FluidMarkerItem.fluidOf(target.toStack(1));
        for (long ask = amount; ask >= 1; ask /= 2) {
            long units = fluid == null ? ask : ask * FluidAmounts.PER_BUCKET;
            long made = Math.ceilDiv(units, batch) * batch;
            if (Math.min(made, needed) > room) continue;
            // Results return through the Deck before the port exports them. Voiding is not room.
            long returning = made + pending;
            long deckRoom = fluid == null ? storage.room(target, returning) : storage.roomFluid(fluid, returning);
            if (deckRoom < returning) continue;
            String problem = Jobs.start(player, deck, target, ask, null, id, false);
            if (problem == null) return true;
            if (!"message.jasm.craft.missing".equals(problem) && !"message.jasm.craft.too_big".equals(problem)) return false;
        }
        return false;
    }

    /** Dry-run the actual output handlers together, including their slot rules and Stock limits. */
    private static long room(TransferPortBlockEntity port, ServerLevel world, int row, ItemStack target) {
        FluidResource fluid = FluidMarkerItem.fluidOf(target);
        long room = 0;
        try (Transaction tx = Transaction.openRoot()) {
            for (Direction side : port.workFaces()) {
                BlockPos pos = port.getBlockPos().relative(side);
                if (fluid != null) {
                    var tank = Machines.fluidInlet(world, pos, side.getOpposite());
                    if (tank == null) continue;
                    long left = port.hasStockUpgrade() ? PortStock.of(PortStock.fluidsLeft(port.filters().output(), tank), row) : Integer.MAX_VALUE;
                    room += tank.insert(fluid, (int) Math.min(left, Integer.MAX_VALUE - room), tx);
                } else {
                    var inventory = Machines.inlet(world, pos, side.getOpposite());
                    if (inventory == null) continue;
                    long left = port.hasStockUpgrade() ? PortStock.of(PortStock.itemsLeft(port.filters().output(), inventory), row) : Integer.MAX_VALUE;
                    room += inventory.insert(ItemResource.of(target), (int) Math.min(left, Integer.MAX_VALUE - room), tx);
                }
            }
            // No commit: checking room must not move anything.
        }
        return room;
    }

    /** Count jobs for this Deck before another port can request the same missing items. */
    private static long pending(ServerLevel world, ItemStack deck, WaferSettings.Filter rule, boolean fluid) {
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        long pending = 0;
        for (AutocraftState.Job entry : AutocraftState.get(world.getServer()).jobs()) {
            if (entry.finished() || entry.deck().filter(id -> id.equals(deckId)).isEmpty()) continue;
            var place = entry.server();
            ServerLevel level = world.getServer().getLevel(place.dimension());
            // An unloaded job may still supply the Deck; wait until its result can be checked.
            if (level == null || !level.isLoaded(place.pos())) return Integer.MAX_VALUE;
            if (!(level.getBlockEntity(place.pos()) instanceof CraftingServerBlockEntity server) || server.job() == null) return Integer.MAX_VALUE;
            var job = server.job();
            if (job.toPlayer() || job.target() == null) continue;
            ItemStack target = job.target().create();
            FluidResource key = FluidMarkerItem.fluidOf(target);
            if (fluid ? key != null && rule.matches(key.getFluid()) : key == null && rule.matches(target.getItem())) {
                pending = Math.min(Integer.MAX_VALUE, pending + Math.min(Integer.MAX_VALUE, job.amount()));
            }
        }
        return pending;
    }

    /** What the network's recipe cards make, in card order. Materials can't be asked for. */
    private static List<ItemStack> results(ServerLevel world, CableNetwork network, ServerPlayer player) {
        List<ItemStack> results = new ArrayList<>();
        Map<ItemResource, Integer> batches = new HashMap<>();
        for (Card card : Jobs.cards(network, player)) {
            ItemStack result;
            if (card instanceof RecipeCard crafting) {
                var resolved = CardRecipes.resolve(world, crafting);
                if (resolved == null) continue;
                result = resolved.output();
                batches.putIfAbsent(ItemResource.of(result), result.getCount());
            } else {
                ProcessingCard processing = (ProcessingCard) card;
                if (processing.usedInputs().isEmpty()
                        || Machines.spots(world, network, processing).stream().noneMatch(at -> Machines.reach(world, network, at) != null)) continue;
                result = processing.result().copyWithCount(processing.main().count());
            }
            if (!result.isEmpty() && !MaterialMarkerItem.isMarker(result)) results.add(result);
        }
        // The planner chooses crafting recipes before processing cards for the same result.
        for (ItemStack result : results) {
            Integer batch = batches.putIfAbsent(ItemResource.of(result), result.getCount());
            if (batch != null) result.setCount(batch);
        }
        return results;
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
