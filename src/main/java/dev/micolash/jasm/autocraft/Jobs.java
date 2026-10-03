package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.CraftPlanner;
import dev.micolash.jasm.network.CableNetwork;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Everything about crafting jobs: planning a request, starting it, running its crafts, and handing the results back.
 * The work is split by stage: {@link JobPlanning}, {@link JobRunner}, {@link JobReturns} and {@link JobRecovery}. This
 * class is the one door the rest of the mod uses.
 *
 * <p>A job's items live in a hidden record of the wafer store. Taking ingredients from the requester's wafers and
 * putting results back change both records with the requester as the one who changed them, so both are written after
 * the requester's file, the same as any Deck operation. A craft takes its ingredients and adds its result in one step
 * when it finishes, so a crash can rewind a job, but never lose or copy what it held.
 */
public final class Jobs {
    private Jobs() {}

    /** A server a request could go to. */
    public record ServerOption(BlockPos pos, int memory, int parallel, boolean busy, boolean fits) {}

    /** A planned request: the plan, the servers, and which one it would go to (-1 when none can take it). */
    public record Preview(CraftPlanner.Plan<ItemResource> plan, List<ServerOption> servers, int chosen, @Nullable String problem) {}

    /** The network a paired Deck belongs to, if its terminal stands in a loaded spot and has power. */
    public static @Nullable EncodingTerminalBlockEntity terminalOf(MinecraftServer server, ItemStack deck) {
        return JobPlanning.terminalOf(server, deck);
    }

    /** The Encoding Terminal with this identity, if it stands in a loaded spot. */
    public static @Nullable EncodingTerminalBlockEntity terminalById(MinecraftServer server, UUID id) {
        return JobPlanning.terminalById(server, id);
    }

    /** Whether the block at {@code pos} is on the network this Deck is paired with. */
    public static boolean onDeckNetwork(ItemStack deck, ServerLevel level, BlockPos pos) {
        return JobPlanning.onDeckNetwork(deck, level, pos);
    }

    /** Cards on {@code network} in racks with power that {@code player} may use. */
    public static List<Card> cards(CableNetwork network, ServerPlayer player) {
        return JobPlanning.cards(network, player);
    }

    /** Whether a processing card has a machine it can use: one of its ports stands on the network and faces something. */
    static boolean reachable(ServerLevel level, @Nullable CableNetwork network, ProcessingCard card) {
        return JobPlanning.reachable(level, network, card);
    }

    /** Plans {@code amount} of {@code target} for {@code player}'s Crafting Deck, without changing anything. */
    public static Preview preview(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted) {
        return JobPlanning.preview(player, deck, target, amount, wanted);
    }

    /**
     * Starts a request: plans it again (the screen is never trusted), takes every ingredient from the Deck's wafers
     * at once, and gives the job to the chosen server. Returns null when it started, or the message saying why not.
     */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted) {
        return JobPlanning.start(player, deck, target, amount, wanted);
    }

    /** As {@link #start(ServerPlayer, ItemStack, ItemResource, long, BlockPos)}, for a rule of the Deck ({@code rule} is its id). */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted,
            @Nullable UUID rule) {
        return JobPlanning.start(player, deck, target, amount, wanted, rule);
    }

    /** As above; {@code toPlayer}: the results go into the player's inventory instead of onto the Deck. */
    public static @Nullable String start(ServerPlayer player, ItemStack deck, ItemResource target, long amount, @Nullable BlockPos wanted,
            @Nullable UUID rule, boolean toPlayer) {
        return JobPlanning.start(player, deck, target, amount, wanted, rule, toPlayer);
    }

    /** One tick of a server's job. {@code powered} is whether the server paid for this tick. */
    static void tick(ServerLevel level, CraftingServerBlockEntity server, boolean powered) {
        JobRunner.tick(level, server, powered);
    }

    /** The requester stops future crafts; running ones finish and everything left goes back. */
    public static boolean cancel(ServerPlayer player, CraftingServerBlockEntity server) {
        return JobRunner.cancel(player, server);
    }

    /** Cancels the job on {@code pos} if it belongs to this Deck (the Deck's Craft tab). */
    public static boolean cancel(ServerPlayer player, ItemStack deck, BlockPos pos) {
        return JobRunner.cancel(player, deck, pos);
    }

    /**
     * Takes a finished job's results into the player's inventory at the server, for when the Deck is lost. The
     * requester may collect. Returns how many items came out.
     */
    public static long collect(ServerPlayer player, CraftingServerBlockEntity server) {
        return JobReturns.collect(player, server);
    }

    /** The Deck with this identity in the player's inventory. */
    public static ItemStack findDeck(ServerPlayer player, @Nullable UUID deckId) {
        return JobReturns.findDeck(player, deckId);
    }

    /** An open Deck screen shows its wafers' contents; tell it they changed. */
    public static void refreshOpenDeck(ServerPlayer player, ItemStack deck) {
        JobReturns.refreshOpenDeck(player, deck);
    }

    /** The server is being broken: its job ends and everything it held spills on the ground. */
    static void spill(ServerLevel level, CraftingServerBlockEntity server) {
        JobReturns.spill(level, server);
    }

    /** A job the list says belongs here, but the block lost (its chunk was saved before the job started): it returns its items. */
    public static void adopt(ServerLevel level, CraftingServerBlockEntity server) {
        JobRecovery.adopt(level, server);
    }

    /**
     * A chunk with a port or server of this job is being saved and unloaded: the job's items are written too, so a
     * crash later can't find a set both inside the machine and back in the job.
     */
    static void writeNow(MinecraftServer server, UUID jobId) {
        JobRecovery.writeNow(server, jobId);
    }

    /** Null means a locked job is unloaded: keep its possible returns until it can be checked. */
    static @Nullable Set<ItemResource> expectedPortReturns(ServerLevel level, AccessPortBlockEntity port) {
        return JobRecovery.expectedPortReturns(level, port);
    }
}
