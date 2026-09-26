package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.network.TrustList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trusting players at an Encoding Terminal: owner only. A name is looked up among online players first, then in the
 * server's name cache in the background, like at the Archive.
 */
public final class TerminalAccess {
    /** Owners with a name lookup running; one at a time each. */
    private static final Set<UUID> LOOKUPS = new HashSet<>();

    private TerminalAccess() {}

    /** Sends the open terminal's trust list to its screen. */
    public static void send(ServerPlayer player, EncodingTerminalMenu menu, EncodingTerminalBlockEntity terminal) {
        if (player.connection.hasChannel(CraftPayloads.TrustView.TYPE)) {
            PacketDistributor.sendToPlayer(player, new CraftPayloads.TrustView(menu.containerId, terminal.isOwner(player), terminal.trust()));
        }
    }

    public static void trust(ServerPlayer player, EncodingTerminalMenu menu, EncodingTerminalBlockEntity terminal, String name) {
        if (!terminal.isOwner(player)) {
            return;
        }
        String wanted = name.trim();
        MinecraftServer server = player.level().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(wanted);
        if (online != null) {
            add(player, menu, terminal, new NameAndId(online.getUUID(), online.getPlainTextName()));
            return;
        }
        if (!StringUtil.isValidPlayerName(wanted) || !LOOKUPS.add(player.getUUID())) {
            Notices.bad(player, Component.translatable("message.jasm.archive.player_not_found"));
            return;
        }
        CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(wanted), Util.nonCriticalIoPool()).handleAsync((found, failure) -> {
            LOOKUPS.remove(player.getUUID());
            if (failure != null) {
                Jasm.LOGGER.warn("Could not look up player {}", wanted, failure);
            }
            if (found == null || found.isEmpty()) {
                Notices.bad(player, Component.translatable("message.jasm.archive.player_not_found"));
            } else if (!terminal.isRemoved()) {
                add(player, menu, terminal, found.get());
            }
            return null;
        }, server);
    }

    private static void add(ServerPlayer player, EncodingTerminalMenu menu, EncodingTerminalBlockEntity terminal, NameAndId target) {
        if (target.id().equals(terminal.owner())) {
            Notices.bad(player, Component.translatable("message.jasm.archive.is_owner"));
            return;
        }
        if (terminal.trust().isFull() && !terminal.trust().contains(target.id())) {
            Notices.bad(player, Component.translatable("message.jasm.terminal.trust_full", TrustList.MAX));
            return;
        }
        terminal.setTrust(terminal.trust().with(target.id(), target.name()));
        Notices.good(player, Component.translatable("message.jasm.terminal.trusted", target.name()));
        if (player.containerMenu == menu) {
            send(player, menu, terminal);
        }
    }

    public static void untrust(ServerPlayer player, EncodingTerminalMenu menu, EncodingTerminalBlockEntity terminal, UUID target) {
        if (terminal.isOwner(player)) {
            terminal.setTrust(terminal.trust().without(target));
            send(player, menu, terminal);
        }
    }
}
