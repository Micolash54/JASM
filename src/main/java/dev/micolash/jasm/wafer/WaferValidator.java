package dev.micolash.jasm.wafer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link StampPolicy} to a physical wafer stack, changing the stack in place: copies are blanked,
 * recovered originals are emptied (count 0), valid wafers may be re-stamped. A wafer is only ever wiped when it is
 * known to be a copy. Anything that merely cannot be checked right now (unknown or unreadable record, capacity
 * mismatch, an older copy whose newer twin has not shown up) keeps its identity and is refused access, so fixing
 * the cause later brings it back.
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

    public static Verdict validate(WaferStore store, ItemStack stack, Mode mode, @Nullable Player holder) {
        if (stack.isEmpty()) {
            // Already consumed (for example dissolved by an earlier check): nothing to grant access to.
            return Verdict.UNFORMATTED;
        }
        if (!(stack.getItem() instanceof WaferItem wafer)) {
            throw new IllegalArgumentException("Not a wafer: " + stack);
        }
        if (WaferMerge.isPending(stack) && !WaferMerge.settle(store, stack, holder)) {
            // Crafted from wafers whose records can't be read yet: locked until they can.
            if (mode != Mode.PASSIVE) {
                notify(holder, "message.jasm.wafer.unreadable");
            }
            return Verdict.UNREADABLE;
        }
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            return Verdict.UNFORMATTED;
        }
        WaferStore.Lookup lookup = store.find(identity);
        if (lookup.status() == WaferStore.Status.UNREADABLE) {
            if (mode != Mode.PASSIVE) {
                Jasm.LOGGER.warn("Wafer #{} has a record that cannot be read; locked until it can (holder {})",
                        identity.serial(), holderName(holder));
                notify(holder, "message.jasm.wafer.unreadable");
            }
            return Verdict.UNREADABLE;
        }
        WaferRecord record = lookup.record();
        Verdict verdict = StampPolicy.judge(identity.stamp(), wafer.tier().capacity(), record == null ? null : record.view());
        if (record != null && record.types() != wafer.tier().types() && verdict != Verdict.ORPHAN) {
            // Same total but a different kind of wafer (an 8 Type Wafer and a 64K hold as much): never the same wafer.
            verdict = Verdict.TAMPERED;
        }
        // Locked wafers are reported when someone tries to use them, not on every background check.
        boolean report = mode != Mode.PASSIVE;
        switch (verdict) {
            case UNFORMATTED, UNREADABLE -> {
            }
            case ORPHAN -> {
                if (report) {
                    Jasm.LOGGER.warn("Wafer #{} ({}) has no matching record; locked (holder {})", identity.serial(), identity.id(),
                            holderName(holder));
                    notify(holder, "message.jasm.wafer.unknown");
                }
            }
            case TAMPERED -> {
                if (report) {
                    Jasm.LOGGER.error("Wafer #{} capacity mismatch (item {}, record {}); locked (holder {})",
                            identity.serial(), wafer.tier().capacity(), record.capacity(), holderName(holder));
                    notify(holder, "message.jasm.wafer.unknown");
                }
            }
            case STALE -> {
                if (report) {
                    Jasm.LOGGER.warn("Wafer #{} is older than its record (stamp {}, record {}) and the newer copy has not shown up"
                            + " since the server started; locked (holder {})", identity.serial(), identity.stamp(), record.current(),
                            holderName(holder));
                    notify(holder, "message.jasm.wafer.stale");
                }
            }
            case DUPLICATE -> {
                stack.remove(JasmComponents.WAFER_IDENTITY.get());
                Jasm.LOGGER.info("Duplicate of wafer #{} (stamp {}, current {}) blanked (holder {})",
                        identity.serial(), identity.stamp(), record.current(), holderName(holder));
                notify(holder, "message.jasm.wafer.duplicate_blanked");
            }
            case RECOVERED_ORIGINAL -> {
                stack.setCount(0);
                Jasm.LOGGER.info("Recovered original of wafer #{} dissolved (holder {})", identity.serial(), holderName(holder));
                notify(holder, "message.jasm.wafer.recovered_elsewhere");
            }
            case VALID_AHEAD -> {
                store.fastForward(record, identity.stamp());
                Jasm.LOGGER.info("Wafer #{} ahead of its record; fast-forwarded to {}", identity.serial(), identity.stamp());
                if (mode == Mode.ACTIVATE) {
                    restamp(store, record, stack, identity, holder);
                }
            }
            case VALID -> {
                store.markSeen(record);
                if (mode == Mode.ACTIVATE) {
                    restamp(store, record, stack, identity, holder);
                }
            }
        }
        return verdict;
    }

    /** Creates the record for a blank wafer. The stack must be unformatted. */
    public static WaferRecord format(WaferStore store, ItemStack stack, @Nullable Player holder) {
        if (!(stack.getItem() instanceof WaferItem wafer) || stack.has(JasmComponents.WAFER_IDENTITY.get())) {
            throw new IllegalArgumentException("Can only format a blank wafer: " + stack);
        }
        WaferRecord record = store.create(wafer.tier(), holder);
        stack.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), record.serial(), record.current()));
        return record;
    }

    /** Gives a crafted wafer that is still waiting (see {@link WaferMerge}) its own new record. */
    static WaferRecord formatPending(WaferStore store, ItemStack stack, @Nullable Player holder) {
        stack.remove(JasmComponents.WAFER_MERGE.get());
        return format(store, stack, holder);
    }

    /** The record of a stack that has just been validated as granting access. */
    public static Optional<WaferRecord> record(WaferStore store, ItemStack stack) {
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        return identity == null ? Optional.empty() : Optional.ofNullable(store.find(identity).record());
    }

    private static void restamp(WaferStore store, WaferRecord record, ItemStack stack, WaferIdentity identity, @Nullable Player holder) {
        Stamp stamp = store.rotate(record, holder);
        stack.set(JasmComponents.WAFER_IDENTITY.get(), identity.withStamp(stamp));
    }

    private static void notify(@Nullable Player holder, String key) {
        Notices.bad(holder, Component.translatable(key));
    }

    private static String holderName(@Nullable Player holder) {
        return holder == null ? "-" : holder.getPlainTextName();
    }
}
