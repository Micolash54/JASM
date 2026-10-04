package dev.micolash.jasm.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.JasmState;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferTier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Operator support commands. Wafers are named by their number, which admins see in tooltips; {@code /jasm wafer held}
 * shows the numbers of the wafer or Deck in hand.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class JasmCommands {
    private JasmCommands() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jasm")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("storage").then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource()))))
                .then(Commands.literal("wafer")
                        .then(Commands.literal("held").executes(ctx -> held(ctx.getSource())))
                        .then(Commands.literal("info").then(Commands.argument("number", LongArgumentType.longArg(1))
                                .executes(ctx -> info(ctx.getSource(), LongArgumentType.getLong(ctx, "number")))))
                        .then(Commands.literal("restore").then(Commands.argument("number", LongArgumentType.longArg(1))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> restore(ctx.getSource(), LongArgumentType.getLong(ctx, "number"),
                                                EntityArgument.getPlayer(ctx, "player"))))))));
    }

    private static int stats(CommandSourceStack source) {
        WaferStore store = WaferStore.get(source.getServer());
        JasmState state = store.state();
        source.sendSuccess(() -> Component.literal(("JASM storage: epoch %d, next wafer #%d, %d wafer records loaded (%d not yet saved,"
                + " %d unreadable), %d archives (%d unreadable)").formatted(state.epoch(), state.nextSerial(), store.loadedCount(),
                        store.unsavedCount(), store.unreadableCount(), state.archiveCount(), state.unreadableArchiveCount())),
                false);
        return 1;
    }

    private static int info(CommandSourceStack source, long serial) {
        WaferStore store = WaferStore.get(source.getServer());
        Optional<WaferRecord> found = store.bySerial(serial);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal(store.isUnreadable(serial)
                    ? "Wafer #" + serial + " has a record that cannot be read (see the server log). It is kept unchanged."
                    : "No wafer #" + serial));
            return 0;
        }
        WaferRecord r = found.get();
        WaferRecord.History h = r.history();
        String lastUsed = h.lastUsedAt() == 0
                ? "never"
                : h.lastUsedBy() + " at " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(h.lastUsedAt()));
        source.sendSuccess(() -> Component.literal(("Wafer #%d (%s): %d/%d items (%d from missing mods), %d variants, stamp %s (saved %s),"
                + " floor %s, archive %s, created by %s, last used by %s").formatted(r.serial(), r.id(), r.used(), r.capacity(),
                        r.quarantinedCount(), r.contents().size(), r.current(), r.confirmed(), r.recoveryFloor(), r.archiveId(),
                        h.createdBy().isEmpty() ? "-" : h.createdBy(), lastUsed)),
                false);
        return 1;
    }

    /** Details of the wafer in the main hand, or of every wafer in a Deck held there. */
    private static int held(CommandSourceStack source) throws CommandSyntaxException {
        ItemStack stack = source.getPlayerOrException().getMainHandItem();
        List<Long> serials = new ArrayList<>();
        Consumer<ItemStack> collect = wafer -> {
            WaferIdentity identity = wafer.get(JasmComponents.WAFER_IDENTITY.get());
            if (identity != null) {
                serials.add(identity.serial());
            }
        };
        if (stack.getItem() instanceof WaferItem) {
            collect.accept(stack);
        } else if (stack.getItem() instanceof WaferHolderItem holder) {
            holder.forEachWafer(stack, collect);
        }
        if (serials.isEmpty()) {
            source.sendFailure(Component.literal("Hold a used wafer, or a Deck with used wafers, in your main hand"));
            return 0;
        }
        int found = 0;
        for (long serial : serials) {
            found += info(source, serial);
        }
        return found;
    }

    private static int restore(CommandSourceStack source, long serial, ServerPlayer player) {
        WaferStore store = WaferStore.get(source.getServer());
        Optional<WaferRecord> found = store.bySerial(serial);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("No readable wafer #" + serial));
            return 0;
        }
        WaferRecord record = found.get();
        Optional<WaferTier> tier = WaferTier.byLimits(record.kind(), record.capacity(), record.types());
        if (tier.isEmpty()) {
            source.sendFailure(Component.literal("No wafer tier holds " + record.capacity() + " items in " + record.types() + " types"));
            return 0;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            source.sendFailure(Component.literal(player.getPlainTextName() + " has no free inventory slot"));
            return 0;
        }
        Stamp stamp = store.reissue(record, record.capacity(), player);
        ItemStack wafer = new ItemStack(JasmItems.wafer(tier.get()));
        wafer.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), record.serial(), stamp));
        player.getInventory().add(wafer);
        Jasm.LOGGER.info("{} restored wafer #{} to {}", source.getTextName(), serial, player.getPlainTextName());
        source.sendSuccess(() -> Component.literal("Restored wafer #" + serial + " to " + player.getPlainTextName()), true);
        return 1;
    }
}
