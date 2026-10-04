package dev.micolash.jasm.pool;

import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Finds the network pool a Deck may use right now. Anything short of a full answer means no pool. */
public final class PoolAccess {
    private PoolAccess() {}

    public static @Nullable NetworkPool forDeck(ServerPlayer player, ItemStack deck) {
        if (!deck.has(JasmComponents.DECK_NETWORK.get())) return null;
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        MinecraftServer server = player.level().getServer();
        var terminal = Jobs.terminalOf(server, deck);
        if (deckId == null || terminal == null || !(terminal.getLevel() instanceof ServerLevel level)
                || !level.isLoaded(terminal.getBlockPos()) || !terminal.running()
                || !AutocraftState.get(server).isPaired(terminal.terminalId(), player.getUUID(), deckId)
                || !MachineAccess.canUse(terminal, player) || !DeckItem.worksIn(deck, level))
            return null;
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        return network == null ? null : NetworkPool.of(level, network);
    }
}
