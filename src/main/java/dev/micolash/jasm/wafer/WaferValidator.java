package dev.micolash.jasm.wafer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.ledger.JasmLedger;
import dev.micolash.jasm.ledger.WaferRecord;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link StampPolicy} to a physical wafer stack, mutating the stack in place:
 * copies are blanked, recovered originals are emptied (count 0), valid wafers may be re-stamped.
 */
public final class WaferValidator {
    public enum Mode {
        /** Deck insertion or Deck open: a valid wafer receives a fresh stamp. */
        ACTIVATE,
        /** Before a grid operation: never mints. */
        CHECK,
        /** Background inventory tick or Archive slot: never mints. */
        PASSIVE
    }

    private WaferValidator() {}

    public static Verdict validate(JasmLedger ledger, ItemStack stack, Mode mode, @Nullable Player holder) {
        if (!(stack.getItem() instanceof WaferItem wafer)) {
            throw new IllegalArgumentException("Not a wafer: " + stack);
        }
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        WaferRecord record = identity == null ? null : ledger.wafer(identity.id()).orElse(null);
        Verdict verdict = StampPolicy.judge(identity == null ? null : identity.stamp(), wafer.tier().capacity(),
                record == null ? null : record.view());
        switch (verdict) {
            case UNFORMATTED -> {
            }
            case ORPHAN -> {
                stack.remove(JasmComponents.WAFER_IDENTITY.get());
                Jasm.LOGGER.warn("Wafer {} has no ledger record; blanked (holder {})", identity.id(), holderName(holder));
            }
            case TAMPERED -> {
                stack.remove(JasmComponents.WAFER_IDENTITY.get());
                Jasm.LOGGER.error("Wafer {} capacity mismatch (item {}, record {}); blanked, record kept (holder {})",
                        identity.id(), wafer.tier().capacity(), record.capacity(), holderName(holder));
            }
            case DUPLICATE -> {
                stack.remove(JasmComponents.WAFER_IDENTITY.get());
                Jasm.LOGGER.info("Duplicate of wafer {} (stamp {}, current {}) blanked (holder {})",
                        identity.id(), identity.stamp(), record.current(), holderName(holder));
                notify(holder, "message.jasm.wafer.duplicate_blanked");
            }
            case RECOVERED_ORIGINAL -> {
                stack.setCount(0);
                Jasm.LOGGER.info("Recovered original of wafer {} dissolved (holder {})", identity.id(), holderName(holder));
                notify(holder, "message.jasm.wafer.recovered_elsewhere");
            }
            case VALID_AHEAD -> {
                ledger.fastForward(record, identity.stamp());
                Jasm.LOGGER.info("Wafer {} ahead of ledger; fast-forwarded to {}", identity.id(), identity.stamp());
                if (mode == Mode.ACTIVATE) {
                    restamp(ledger, record, stack);
                }
            }
            case VALID -> {
                if (mode == Mode.ACTIVATE) {
                    restamp(ledger, record, stack);
                }
            }
        }
        return verdict;
    }

    /** Creates a ledger record for a blank wafer. The stack must be unformatted. */
    public static WaferRecord format(JasmLedger ledger, ItemStack stack) {
        if (!(stack.getItem() instanceof WaferItem wafer) || stack.has(JasmComponents.WAFER_IDENTITY.get())) {
            throw new IllegalArgumentException("Can only format a blank wafer: " + stack);
        }
        WaferRecord record = ledger.createWafer(wafer.tier().capacity());
        stack.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), record.current()));
        return record;
    }

    /** The record of a stack that has just been validated as granting access. */
    public static Optional<WaferRecord> record(JasmLedger ledger, ItemStack stack) {
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        return identity == null ? Optional.empty() : ledger.wafer(identity.id());
    }

    private static void restamp(JasmLedger ledger, WaferRecord record, ItemStack stack) {
        Stamp stamp = ledger.rotate(record);
        stack.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), stamp));
    }

    private static void notify(@Nullable Player holder, String key) {
        if (holder != null) {
            holder.sendOverlayMessage(Component.translatable(key));
        }
    }

    private static String holderName(@Nullable Player holder) {
        return holder == null ? "-" : holder.getPlainTextName();
    }
}
