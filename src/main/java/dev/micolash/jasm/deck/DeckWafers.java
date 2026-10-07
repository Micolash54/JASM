package dev.micolash.jasm.deck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The wafers inside a Deck, by slot. Immutable: reading a slot creates a fresh stack, and changing one builds a new
 * value that the caller stores back on the Deck. Nothing inside can be changed by accident through a shared stack.
 */
public record DeckWafers(List<Entry> entries) {
    /** Slot indexes a Deck can save: 0 up to one below this. */
    public static final int MAX_SLOTS = 1_024;

    public record Entry(int slot, ItemStackTemplate wafer) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, MAX_SLOTS - 1).fieldOf("slot").forGetter(Entry::slot),
                ItemStackTemplate.CODEC.fieldOf("item").forGetter(Entry::wafer))
                .apply(i, Entry::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::slot,
                ItemStackTemplate.STREAM_CODEC, Entry::wafer,
                Entry::new);
    }

    public static final DeckWafers EMPTY = new DeckWafers(List.of());

    public static final Codec<DeckWafers> CODEC = Entry.CODEC.listOf().xmap(DeckWafers::new, DeckWafers::entries);

    public static final StreamCodec<RegistryFriendlyByteBuf, DeckWafers> STREAM_CODEC = Entry.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(DeckWafers::new, DeckWafers::entries);

    public DeckWafers {
        entries = entries.stream().sorted(Comparator.comparingInt(Entry::slot)).toList();
    }

    /** A fresh copy of the wafer in {@code slot}, or empty. */
    public ItemStack get(int slot) {
        for (Entry entry : entries) {
            if (entry.slot() == slot) {
                return entry.wafer().create();
            }
        }
        return ItemStack.EMPTY;
    }

    /** A new value with {@code slot} holding a copy of {@code stack} (or emptied if the stack is empty). */
    public DeckWafers with(int slot, ItemStack stack) {
        List<Entry> updated = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.slot() != slot) {
                updated.add(entry);
            }
        }
        if (!stack.isEmpty()) {
            updated.add(new Entry(slot, ItemStackTemplate.fromNonEmptyStack(stack)));
        }
        return new DeckWafers(updated);
    }

    public int count() {
        return entries.size();
    }

    /** The highest slot holding a wafer, or -1. */
    public int highestSlot() {
        return entries.isEmpty() ? -1 : entries.getLast().slot();
    }

    /** Wafers in slots below {@code slots}. */
    public int countBelow(int slots) {
        return (int) entries.stream().filter(entry -> entry.slot() < slots).count();
    }

    /** Fresh copies of every wafer, for reading. */
    public void forEach(Consumer<ItemStack> action) {
        entries.forEach(entry -> action.accept(entry.wafer().create()));
    }
}
