package dev.micolash.jasm.wafer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A wafer crafted from three smaller ones, not combined yet. The crafting grid only notes which wafers went in;
 * their items move to a new record the first time a player holds the result on the server. Until then the items
 * stay in the old records, so a crafted wafer that nobody has picked up yet (from a Crafter, say) loses nothing.
 *
 * <p>{@code givers} are the input wafers that may hand their settings (filter rows and name) to the new wafer, in
 * grid order. The first one that has any gives all of its settings; the others give none.
 */
public record WaferMerge(List<WaferIdentity> sources, List<Giver> givers) {
    /** One input wafer in grid order: its record, if it had one, and the name it carried, if any. */
    public record Giver(Optional<WaferIdentity> wafer, Optional<Component> name) {
        static final Codec<Giver> CODEC = RecordCodecBuilder.create(i -> i.group(
                WaferIdentity.CODEC.optionalFieldOf("wafer").forGetter(Giver::wafer),
                ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Giver::name))
                .apply(i, Giver::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Giver> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(WaferIdentity.STREAM_CODEC), Giver::wafer,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), Giver::name,
                Giver::new);
    }

    private static final Codec<WaferMerge> WITH_GIVERS = RecordCodecBuilder.create(i -> i.group(
            WaferIdentity.CODEC.listOf().fieldOf("sources").forGetter(WaferMerge::sources),
            Giver.CODEC.listOf().optionalFieldOf("givers", List.of()).forGetter(WaferMerge::givers))
            .apply(i, WaferMerge::new));
    /** Wafers crafted before settings carried over saved just the list of sources. */
    public static final Codec<WaferMerge> CODEC = Codec.withAlternative(WITH_GIVERS,
            WaferIdentity.CODEC.listOf().xmap(sources -> new WaferMerge(sources, List.of()), WaferMerge::sources));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaferMerge> STREAM_CODEC = StreamCodec.composite(
            WaferIdentity.STREAM_CODEC.apply(ByteBufCodecs.list()), WaferMerge::sources,
            Giver.STREAM_CODEC.apply(ByteBufCodecs.list()), WaferMerge::givers,
            WaferMerge::new);

    public WaferMerge {
        sources = List.copyOf(sources);
        givers = List.copyOf(givers);
    }

    /**
     * What a wafer crafted from {@code inputs} (in grid order) notes, or null when no input brings anything: no items and
     * no settings. A formatted input counts itself; an input that is itself still waiting brings its own sources and
     * givers along; a blank input adds only its name, if it has one.
     */
    public static @Nullable WaferMerge of(List<ItemStack> inputs) {
        List<WaferIdentity> sources = new ArrayList<>();
        List<Giver> givers = new ArrayList<>();
        for (ItemStack input : inputs) {
            WaferIdentity identity = input.get(JasmComponents.WAFER_IDENTITY.get());
            WaferMerge pending = input.get(JasmComponents.WAFER_MERGE.get());
            Optional<Component> name = Optional.ofNullable(input.get(DataComponents.CUSTOM_NAME));
            if (identity != null) {
                sources.add(identity);
                givers.add(new Giver(Optional.of(identity), name));
            } else if (pending != null) {
                sources.addAll(pending.sources());
                name.ifPresent(n -> givers.add(new Giver(Optional.empty(), Optional.of(n))));
                givers.addAll(pending.givers());
            } else if (name.isPresent()) {
                givers.add(new Giver(Optional.empty(), name));
            }
        }
        return sources.isEmpty() && givers.isEmpty() ? null : new WaferMerge(sources, givers);
    }

    public static boolean isPending(ItemStack stack) {
        return stack.has(JasmComponents.WAFER_MERGE.get());
    }

    /**
     * Combines a waiting wafer's sources into one new record and gives the stack its identity. Every source that is
     * still the newest copy of its wafer is emptied into the new record and closed: its record jumps past all its
     * copies, so any copy left anywhere dissolves and recovering it gives nothing. A source that has moved on since
     * (a copy was used meanwhile) keeps its items. The first giver with any settings hands over its filter rows and
     * name; an Archive link never carries over, the new wafer is a new wafer. If there are no items and no filter rows to
     * carry over, the stack stays blank (with the name, if any) instead of getting a record of its own. If any source
     * can't be read right now, nothing happens and the wafer keeps waiting. Returns false while it is still waiting.
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
            if (record.kind() != wafer.tier().kind()) {
                Jasm.LOGGER.warn("Wafer #{} is a {} wafer and can't go into a {} wafer; its items stay with it", source.serial(),
                        record.kind(), wafer.tier().kind());
                continue;
            }
            absorbed.add(record);
            total += record.used();
        }
        long capacity = wafer.tier().capacityAmount();
        if (total > capacity) {
            Jasm.LOGGER.error("Crafted wafer can't hold its sources ({} in all, room for {}); it keeps waiting", total, capacity);
            return false;
        }
        // The first giver that has settings hands them all over. A wafer that moved on since gives nothing.
        WaferSettings rules = WaferSettings.DEFAULT;
        Optional<Component> name = Optional.empty();
        for (Giver giver : merge.givers()) {
            WaferSettings giverRules = WaferSettings.DEFAULT;
            if (giver.wafer().isPresent()) {
                WaferRecord record = absorbed.stream().filter(r -> r.id().equals(giver.wafer().get().id())).findFirst().orElse(null);
                if (record == null) {
                    continue;
                }
                giverRules = record.settings();
            }
            if (giver.name().isPresent() || !giverRules.equals(WaferSettings.DEFAULT)) {
                rules = giverRules;
                name = giver.name();
                break;
            }
        }
        stack.remove(JasmComponents.WAFER_MERGE.get());
        name.ifPresent(n -> stack.set(DataComponents.CUSTOM_NAME, n));
        if (total == 0 && rules.equals(WaferSettings.DEFAULT)) {
            // Nothing to carry over but a name: it stays blank, like a wafer crafted from blank ones, so it stacks with
            // them in storage. The old wafers still end, Archive links included, as they do when they held items.
            for (WaferRecord source : absorbed) {
                store.close(source, holder);
            }
            return true;
        }
        WaferRecord target = WaferValidator.format(store, stack, holder);
        store.setSettings(target, rules, holder);
        for (WaferRecord source : absorbed) {
            store.absorb(target, source, holder);
        }
        Jasm.LOGGER.info("Crafted wafer #{} holds {} items from {} wafers", target.serial(), target.used(), absorbed.size());
        return true;
    }
}
