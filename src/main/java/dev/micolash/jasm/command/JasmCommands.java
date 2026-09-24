package dev.micolash.jasm.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.ledger.JasmLedger;
import dev.micolash.jasm.ledger.WaferRecord;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator support commands. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class JasmCommands {
    private JasmCommands() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jasm")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("ledger").then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource()))))
                .then(Commands.literal("wafer")
                        .then(Commands.literal("info").then(Commands.argument("id", UuidArgument.uuid())
                                .executes(ctx -> info(ctx.getSource(), UuidArgument.getUuid(ctx, "id")))))
                        .then(Commands.literal("restore").then(Commands.argument("id", UuidArgument.uuid())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> restore(ctx.getSource(), UuidArgument.getUuid(ctx, "id"),
                                                EntityArgument.getPlayer(ctx, "player"))))))));
    }

    private static int stats(CommandSourceStack source) {
        JasmLedger ledger = JasmLedger.get(source.getServer());
        source.sendSuccess(() -> Component.literal("JASM ledger: epoch %d, %d wafers, %d archives, %d undecodable records"
                .formatted(ledger.epoch(), ledger.wafers().size(), ledger.archiveCount(), ledger.undecodableRecordCount())), false);
        return 1;
    }

    private static int info(CommandSourceStack source, UUID id) {
        Optional<WaferRecord> found = JasmLedger.get(source.getServer()).wafer(id);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("No wafer " + id));
            return 0;
        }
        WaferRecord r = found.get();
        source.sendSuccess(() -> Component.literal("Wafer %s: %d/%d items (%d quarantined), %d variants, current %s, floor %s, archive %s"
                .formatted(r.id(), r.used(), r.capacity(), r.quarantinedCount(), r.contents().size(), r.current(),
                        r.recoveryFloor(), r.archiveId())), false);
        return 1;
    }

    private static int restore(CommandSourceStack source, UUID id, ServerPlayer player) throws CommandSyntaxException {
        JasmLedger ledger = JasmLedger.get(source.getServer());
        Optional<WaferRecord> found = ledger.wafer(id);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("No wafer " + id));
            return 0;
        }
        WaferRecord record = found.get();
        Optional<WaferTier> tier = WaferTier.byCapacity(record.capacity());
        if (tier.isEmpty()) {
            source.sendFailure(Component.literal("No wafer tier has capacity " + record.capacity()));
            return 0;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            source.sendFailure(Component.literal(player.getPlainTextName() + " has no free inventory slot"));
            return 0;
        }
        Stamp stamp = ledger.reissue(record, record.capacity());
        ItemStack wafer = new ItemStack(JasmItems.wafer(tier.get()));
        wafer.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), stamp));
        player.getInventory().add(wafer);
        Jasm.LOGGER.info("{} restored wafer {} to {}", source.getTextName(), id, player.getPlainTextName());
        source.sendSuccess(() -> Component.literal("Restored wafer " + id + " to " + player.getPlainTextName()), true);
        return 1;
    }
}
