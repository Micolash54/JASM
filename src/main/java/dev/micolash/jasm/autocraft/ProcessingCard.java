package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * A recipe for a machine behind an Access Port: what goes in (nine slots, any amount each, positions only for
 * showing), what comes back (up to three, the first one being what the card makes), and which ports it may use.
 * JASM never checks it against the machine: the player says what the machine does.
 */
public record ProcessingCard(List<Amount> inputs, List<Amount> outputs, List<Machine> machines) implements Card {
    public static final int INPUTS = 9;
    public static final int OUTPUTS = 3;
    /** Most of one item in one slot. */
    public static final int MAX_AMOUNT = 999;

    /** Some of one item; empty where a slot is empty. */
    public record Amount(ItemResource item, int count) {
        public static final Amount EMPTY = new Amount(ItemResource.EMPTY, 0);

        static final Codec<Amount> CODEC = RecordCodecBuilder.create(i -> i.group(
                        ItemResource.OPTIONAL_CODEC.fieldOf("item").forGetter(Amount::item),
                        Codec.INT.optionalFieldOf("count", 0).forGetter(Amount::count))
                .apply(i, Amount::of));

        static final StreamCodec<RegistryFriendlyByteBuf, Amount> STREAM_CODEC = StreamCodec.composite(
                ItemResource.STREAM_CODEC, Amount::item,
                ByteBufCodecs.VAR_INT, Amount::count,
                Amount::of);

        /** Empty unless both an item and a count are there; the count kept within 1 and {@link #MAX_AMOUNT}. */
        public static Amount of(ItemResource item, int count) {
            return item.isEmpty() || count <= 0 ? EMPTY : new Amount(item, Math.min(count, MAX_AMOUNT));
        }

        public static Amount of(ItemStack stack) {
            return stack.isEmpty() ? EMPTY : of(ItemResource.of(stack), stack.getCount());
        }

        public boolean isEmpty() {
            return item.isEmpty();
        }

        /** As a stack to show; the count may be more than a stack holds. */
        public ItemStack stack() {
            return isEmpty() ? ItemStack.EMPTY : item.toStack(count);
        }
    }

    /** An Access Port the card may use, with the name it had when the card was written (for the tooltip). */
    public record Machine(BlockPos pos, String name) {
        static final Codec<Machine> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Machine::pos),
                        Codec.STRING.optionalFieldOf("name", "").forGetter(Machine::name))
                .apply(i, Machine::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Machine> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Machine::pos,
                ByteBufCodecs.stringUtf8(128), Machine::name,
                Machine::new);
    }

    public static final Codec<ProcessingCard> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Amount.CODEC.listOf().fieldOf("inputs").forGetter(ProcessingCard::inputs),
                    Amount.CODEC.listOf().fieldOf("outputs").forGetter(ProcessingCard::outputs),
                    Machine.CODEC.listOf().fieldOf("machines").forGetter(ProcessingCard::machines))
            .apply(i, ProcessingCard::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProcessingCard> STREAM_CODEC = StreamCodec.composite(
            Amount.STREAM_CODEC.apply(ByteBufCodecs.list(INPUTS)), ProcessingCard::inputs,
            Amount.STREAM_CODEC.apply(ByteBufCodecs.list(OUTPUTS)), ProcessingCard::outputs,
            Machine.STREAM_CODEC.apply(ByteBufCodecs.list(64)), ProcessingCard::machines,
            ProcessingCard::new);

    public ProcessingCard {
        inputs = padded(inputs, INPUTS);
        outputs = padded(outputs, OUTPUTS);
        machines = List.copyOf(machines);
    }

    private static List<Amount> padded(List<Amount> amounts, int size) {
        List<Amount> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(i < amounts.size() && amounts.get(i) != null ? amounts.get(i) : Amount.EMPTY);
        }
        return List.copyOf(out);
    }

    /** The first output: what the card makes. Empty only on a broken card. */
    public Amount main() {
        return outputs.stream().filter(a -> !a.isEmpty()).findFirst().orElse(Amount.EMPTY);
    }

    @Override
    public ItemStack result() {
        return main().stack();
    }

    /** Every output after the main one: they come back too. */
    public List<Amount> extras() {
        List<Amount> extras = new ArrayList<>();
        boolean first = true;
        for (Amount output : outputs) {
            if (output.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
            } else {
                extras.add(output);
            }
        }
        return extras;
    }

    /** The used input slots, in grid order. */
    public List<Amount> usedInputs() {
        return inputs.stream().filter(a -> !a.isEmpty()).toList();
    }

    /** Ports the card may use. */
    public List<BlockPos> ports() {
        return machines.stream().map(Machine::pos).toList();
    }
}
