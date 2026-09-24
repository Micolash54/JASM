package dev.micolash.jasm.ledger;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import dev.micolash.jasm.Jasm;
import java.util.ArrayList;
import java.util.List;

/**
 * A list codec that never drops data: elements that fail to decode (for example because their mod was removed)
 * are kept verbatim and written back unchanged on the next save.
 */
public final class LenientListCodec<E> implements Codec<LenientListCodec.Lenient<E>> {
    /** Decoded elements plus undecodable raw elements. */
    public record Lenient<E>(List<E> values, List<Dynamic<?>> raw) {
        public static <E> Lenient<E> of(List<E> values) {
            return new Lenient<>(values, List.of());
        }
    }

    private final Codec<E> elementCodec;
    private final String what;

    public LenientListCodec(Codec<E> elementCodec, String what) {
        this.elementCodec = elementCodec;
        this.what = what;
    }

    @Override
    public <T> DataResult<Pair<Lenient<E>, T>> decode(DynamicOps<T> ops, T input) {
        return ops.getStream(input).map(stream -> {
            List<E> values = new ArrayList<>();
            List<Dynamic<?>> raw = new ArrayList<>();
            stream.forEach(element -> {
                DataResult<E> parsed = elementCodec.parse(ops, element);
                parsed.result().ifPresentOrElse(values::add, () -> {
                    raw.add(new Dynamic<>(ops, element));
                    Jasm.LOGGER.warn("Kept undecodable {} verbatim: {}", what, parsed.error().map(e -> e.message()).orElse("?"));
                });
            });
            return Pair.of(new Lenient<>(values, raw), ops.empty());
        });
    }

    @Override
    public <T> DataResult<T> encode(Lenient<E> input, DynamicOps<T> ops, T prefix) {
        ListBuilder<T> list = ops.listBuilder();
        for (E value : input.values()) {
            list.add(elementCodec.encodeStart(ops, value));
        }
        for (Dynamic<?> raw : input.raw()) {
            list.add(raw.convert(ops).getValue());
        }
        return list.build(prefix);
    }
}
