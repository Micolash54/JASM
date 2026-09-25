package dev.micolash.jasm.wafer;

import com.mojang.serialization.Codec;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A wafer crafted from three smaller ones, not combined yet. The crafting grid only notes which wafers went in;
 * their items move to a new record the first time a player holds the result on the server. Until then the items
 * stay in the old records, so a crafted wafer that nobody has picked up yet (from a Crafter, say) loses nothing.
 */
public record WaferMerge(List<WaferIdentity> sources) {
    public static final Codec<WaferMerge> CODEC = WaferIdentity.CODEC.listOf().xmap(WaferMerge::new, WaferMerge::sources);
    public static final StreamCodec<ByteBuf, WaferMerge> STREAM_CODEC =
            WaferIdentity.STREAM_CODEC.apply(ByteBufCodecs.list()).map(WaferMerge::new, WaferMerge::sources);

    public WaferMerge {
        sources = List.copyOf(sources);
    }

    /**
     * The wafers a crafted wafer is made from. A formatted input counts itself; an input that is itself still waiting
     * brings its own sources along; a blank input adds nothing.
     */
    public static List<WaferIdentity> sourcesOf(List<ItemStack> inputs) {
        List<WaferIdentity> sources = new ArrayList<>();
        for (ItemStack input : inputs) {
            WaferIdentity identity = input.get(JasmComponents.WAFER_IDENTITY.get());
            WaferMerge pending = input.get(JasmComponents.WAFER_MERGE.get());
            if (identity != null) {
                sources.add(identity);
            } else if (pending != null) {
                sources.addAll(pending.sources());
            }
        }
        return sources;
    }

    public static boolean isPending(ItemStack stack) {
        return stack.has(JasmComponents.WAFER_MERGE.get());
    }

    /**
     * Combines a waiting wafer's sources into one new record and gives the stack its identity. Every source that is
     * still the newest copy of its wafer is emptied into the new record and closed: its record jumps past all its
     * copies, so any copy left anywhere dissolves and recovering it gives nothing. A source that has moved on since
     * (a copy was used meanwhile) keeps its items. If any source can't be read right now, nothing happens and the
     * wafer keeps waiting. Returns false while it is still waiting.
     */
    public static boolean settle(WaferStore store, ItemStack stack, @Nullable Player holder) {
        WaferMerge merge = stack.get(JasmComponents.WAFER_MERGE.get());
        if (merge == null) {
            return true;
        }
        if (!(stack.getItem() instanceof WaferItem wafer)) {
            stack.remove(JasmComponents.WAFER_MERGE.get());
            return true;
        }
        List<WaferRecord> absorbed = new ArrayList<>();
        long total = 0;
        for (WaferIdentity source : merge.sources()) {
            WaferStore.Lookup lookup = store.find(source);
            if (lookup.status() == WaferStore.Status.UNREADABLE) {
                Jasm.LOGGER.warn("A crafted wafer waits for wafer #{}, whose record can't be read yet", source.serial());
                return false;
            }
            WaferRecord record = lookup.record();
            if (record == null || absorbed.contains(record)) {
                continue;
            }
            Verdict verdict = StampPolicy.judge(source.stamp(), record.capacity(), record.view());
            if (verdict == Verdict.VALID_AHEAD) {
                store.fastForward(record, source.stamp());
            } else if (verdict != Verdict.VALID) {
                Jasm.LOGGER.info("Wafer #{} went into a craft but has moved on since ({}); its items stay with it", source.serial(), verdict);
                continue;
            }
            absorbed.add(record);
            total += record.used();
        }
        int capacity = wafer.tier().capacity();
        if (total > capacity) {
            Jasm.LOGGER.error("Crafted wafer can't hold its sources ({} items, room for {}); it keeps waiting", total, capacity);
            return false;
        }
        WaferRecord target = WaferValidator.formatPending(store, stack, holder);
        for (WaferRecord source : absorbed) {
            store.absorb(target, source, holder);
        }
        Jasm.LOGGER.info("Crafted wafer #{} holds {} items from {} wafers", target.serial(), target.used(), absorbed.size());
        return true;
    }
}
