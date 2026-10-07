package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.wafer.FluidAmounts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

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

    /** Most millibuckets of one fluid in one slot. */
    public static final int MAX_FLUID = 32_000;

    /** Most units of one material in one slot. */
    public static final int MAX_MATERIAL = MAX_FLUID;

    /** Some of one item, some millibuckets of one fluid, or some units of one other mod's material; empty where a slot is empty. */
    public record Amount(ItemResource item, FluidResource fluid, @Nullable MaterialKey material, int count) {
        public static final Amount EMPTY = new Amount(ItemResource.EMPTY, FluidResource.EMPTY, null, 0);

        /** Some of one item, as before fluids. */
        public Amount(ItemResource item, int count) {
            this(item, FluidResource.EMPTY, null, count);
        }

        public static final Codec<Amount> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemResource.OPTIONAL_CODEC.optionalFieldOf("item", ItemResource.EMPTY).forGetter(Amount::item),
                FluidResource.CODEC.optionalFieldOf("fluid", FluidResource.EMPTY).forGetter(Amount::fluid),
                MaterialKey.CODEC.optionalFieldOf("material").forGetter(a -> Optional.ofNullable(a.material)),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Amount::count))
                .apply(i, (item, fluid, material, count) -> of(item, fluid, material.orElse(null), count)));

        static final StreamCodec<RegistryFriendlyByteBuf, Amount> STREAM_CODEC = StreamCodec.of(
                (buf, amount) -> {
                    // 0 an item, 1 a fluid, 2 a material
                    buf.writeByte(amount.isMaterial() ? 2 : amount.isFluid() ? 1 : 0);
                    if (amount.isMaterial()) {
                        MaterialKey.STREAM_CODEC.encode(buf, amount.material);
                    } else if (amount.isFluid()) {
                        FluidResource.STREAM_CODEC.encode(buf, amount.fluid);
                    } else {
                        ItemResource.STREAM_CODEC.encode(buf, amount.item);
                    }
                    ByteBufCodecs.VAR_INT.encode(buf, amount.count);
                },
                buf -> {
                    int kind = buf.readByte();
                    MaterialKey material = kind == 2 ? MaterialKey.STREAM_CODEC.decode(buf) : null;
                    FluidResource fluid = kind == 1 ? FluidResource.STREAM_CODEC.decode(buf) : FluidResource.EMPTY;
                    ItemResource item = kind == 0 ? ItemResource.STREAM_CODEC.decode(buf) : ItemResource.EMPTY;
                    return of(item, fluid, material, ByteBufCodecs.VAR_INT.decode(buf));
                });

        /**
         * Empty unless both something and a count are there; the count kept within 1 and {@link #MAX_AMOUNT} (or
         * {@link #MAX_FLUID} millibuckets, or {@link #MAX_MATERIAL} units). An item wins over a fluid, and a fluid over a
         * material, on a card that somehow has more than one.
         */
        public static Amount of(ItemResource item, FluidResource fluid, @Nullable MaterialKey material, int count) {
            if (count <= 0) {
                return EMPTY;
            }
            if (!item.isEmpty()) {
                return new Amount(item, FluidResource.EMPTY, null, Math.min(count, MAX_AMOUNT));
            }
            if (!fluid.isEmpty()) {
                return new Amount(ItemResource.EMPTY, fluid, null, Math.min(count, MAX_FLUID));
            }
            return material == null ? EMPTY : new Amount(ItemResource.EMPTY, FluidResource.EMPTY, material, Math.min(count, MAX_MATERIAL));
        }

        public static Amount of(ItemResource item, FluidResource fluid, int count) {
            return of(item, fluid, null, count);
        }

        public static Amount of(ItemResource item, int count) {
            return of(item, FluidResource.EMPTY, null, count);
        }

        public static Amount of(FluidResource fluid, int millibuckets) {
            return of(ItemResource.EMPTY, fluid, null, millibuckets);
        }

        public static Amount of(MaterialKey material, int units) {
            return of(ItemResource.EMPTY, FluidResource.EMPTY, material, units);
        }

        /** The stack's item and its count; a marker is its fluid or material, and the count is the millibuckets or units. */
        public static Amount of(ItemStack stack) {
            return of(stack, stack.getCount());
        }

        /** What an example stack stands for, in some amount: an item (count), a fluid marker's fluid or a material marker's material. */
        public static Amount of(ItemStack example, int count) {
            if (example.isEmpty()) {
                return EMPTY;
            }
            FluidResource fluid = FluidMarkerItem.fluidOf(example);
            if (fluid != null) {
                return of(fluid, count);
            }
            MaterialKey material = MaterialMarkerItem.materialOf(example);
            return material != null ? of(material, count) : of(ItemResource.of(example), count);
        }

        public static Amount of(GridKey key, int count) {
            if (key instanceof GridKey.Material material) {
                return of(material.key(), count);
            }
            return key instanceof GridKey.Fluid fluid ? of(fluid.resource(), count) : of(((GridKey.Item) key).resource(), count);
        }

        public boolean isEmpty() {
            return item.isEmpty() && fluid.isEmpty() && material == null;
        }

        public boolean isFluid() {
            return !fluid.isEmpty();
        }

        public boolean isMaterial() {
            return material != null;
        }

        /** What the slot holds, or null when empty. */
        public @Nullable GridKey key() {
            if (isMaterial()) {
                return new GridKey.Material(material);
            }
            if (isFluid()) {
                return new GridKey.Fluid(fluid);
            }
            return item.isEmpty() ? null : new GridKey.Item(item);
        }

        /** The most a slot of this kind holds. */
        public static int max(boolean fluid) {
            return fluid ? MAX_FLUID : MAX_AMOUNT;
        }

        /** The most a slot holding this example stack takes: more for a fluid or material marker than for an item. */
        public static int max(ItemStack example) {
            return FluidMarkerItem.isMarker(example) || MaterialMarkerItem.isMarker(example) ? MAX_FLUID : MAX_AMOUNT;
        }

        /** As a stack to show; the count may be more than a stack holds. A fluid or material shows as its marker. */
        public ItemStack stack() {
            if (isMaterial()) {
                return MaterialMarkerItem.of(material);
            }
            if (isFluid()) {
                return FluidMarkerItem.of(fluid);
            }
            return isEmpty() ? ItemStack.EMPTY : item.toStack(count);
        }

        /** The same thing in another amount. Empty when nothing is left. */
        public Amount withCount(int count) {
            return of(item, fluid, material, count);
        }

        /** The amount for people: "3" for items, "250 mB" or "1.5 B" for fluids, plain units for a material. */
        public String label() {
            return isFluid() ? FluidAmounts.label(count) : String.valueOf(count);
        }
    }

    /**
     * A machine the card may use: its Access Port, the side of the port it touches, and the name it had when the card
     * was written (for the tooltip).
     */
    public record Machine(BlockPos pos, Direction side, String name) {
        static final Codec<Machine> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Machine::pos),
                Direction.CODEC.optionalFieldOf("side", Direction.DOWN).forGetter(Machine::side),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Machine::name))
                .apply(i, Machine::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Machine> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Machine::pos,
                Direction.STREAM_CODEC, Machine::side,
                ByteBufCodecs.stringUtf8(128), Machine::name,
                Machine::new);

        public Machines.At at() {
            return new Machines.At(pos, side);
        }
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

    /** The machines the card may use. */
    public List<Machines.At> spots() {
        return machines.stream().map(Machine::at).toList();
    }
}
