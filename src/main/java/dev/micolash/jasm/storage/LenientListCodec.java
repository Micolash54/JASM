package dev.micolash.jasm.storage;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import dev.micolash.jasm.Jasm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A list codec that never drops data: elements that fail to decode (for example because their mod was removed)
 * are kept verbatim and written back unchanged on the next save. An element that fails to encode is written as a
 * placeholder instead, so one broken element can never stop the whole file from saving.
 */
public final class LenientListCodec<E> implements Codec<LenientListCodec.Lenient<E>> {
    /** Decoded elements plus undecodable raw elements. */
    public record Lenient<E>(List<E> values, List<Dynamic<?>> raw) {
        public static <E> Lenient<E> of(List<E> values) {
            return new Lenient<>(values, List.of());
        }
    }

    /** Writes the stand-in for an element that could not be encoded. It must not decode as a normal element. */
    public interface Placeholder<E> {
        <T> T encode(E value, String error, DynamicOps<T> ops);
    }

    private final Codec<E> elementCodec;
    private final String what;
    private final Placeholder<E> placeholder;

    public LenientListCodec(Codec<E> elementCodec, String what, Placeholder<E> placeholder) {
        this.elementCodec = elementCodec;
        this.what = what;
        this.placeholder = placeholder;
    }

    public LenientListCodec(Codec<E> elementCodec, String what) {
        this(elementCodec, what, new Placeholder<>() {
            @Override
            public <T> T encode(E value, String error, DynamicOps<T> ops) {
                return ops.createMap(Map.of(
                        ops.createString("unsaveable"), ops.createString(String.valueOf(value)),
                        ops.createString("error"), ops.createString(error)));
            }
        });
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
            DataResult<T> encoded = elementCodec.encodeStart(ops, value);
            Optional<T> result = encoded.result();
            if (result.isPresent()) {
                list.add(result.get());
            } else {
                String error = encoded.error().map(e -> e.message()).orElse("?");
                Jasm.LOGGER.error("Could not save {} {}: {}. Saved a placeholder instead", what, value, error);
                list.add(placeholder.encode(value, error, ops));
            }
        }
        for (Dynamic<?> raw : input.raw()) {
            list.add(raw.convert(ops).getValue());
        }
        return list.build(prefix);
    }
}
