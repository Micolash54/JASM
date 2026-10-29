package dev.micolash.jasm.dock;

import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A player's Deck that a port, job, rule or delivery may work with right now, and who acts for it. Every route finds
 * its Deck here: in a working Docking Station first (whether or not its player is online), else carried by its player while
 * they are online.
 *
 * <p>For a docked Deck the one acting is a stand-in for the Deck's player, standing at the dock: it holds nothing and
 * hears nothing, so nothing may ever be handed to its inventory.
 */
public record DeckReach(ServerPlayer actor, ItemStack deck, @Nullable DockingStationBlockEntity dock) {
    public boolean docked() {
        return dock != null;
    }

    /** {@code player}'s Deck with identity {@code deckId}, or null when no route can reach it now. */
    public static @Nullable DeckReach find(MinecraftServer server, UUID player, @Nullable UUID deckId) {
        return find(server, player, deckId, ItemStack.EMPTY);
    }

    /**
     * As above. {@code slotDeck} is a Deck sitting in a port's own Deck slot: like a carried one, it is used only while
     * its player is online.
     */
    public static @Nullable DeckReach find(MinecraftServer server, UUID player, @Nullable UUID deckId, ItemStack slotDeck) {
        if (deckId == null) return null;
        DockingStationBlockEntity dock = DockingStations.byDeck(deckId);
        if (dock != null) {
            // Docked: that Deck or none. A copy carried somewhere is never used beside it.
            ItemStack docked = dock.usableDeck(player, deckId);
            return docked.isEmpty() ? null : new DeckReach(dock.actor(), docked, dock);
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online == null) return null;
        ItemStack carried = Jobs.findDeck(online, deckId);
        if (carried.isEmpty() && deckId.equals(slotDeck.get(JasmComponents.DECK_ID.get()))) carried = slotDeck;
        return carried.isEmpty() ? null : new DeckReach(online, carried, null);
    }
}
