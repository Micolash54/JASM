package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** One wafer's ordered filters. The first enabled match decides; an empty list accepts everything. */
public record WaferSettings(List<Filter> rules) {
    public enum Mode implements StringRepresentable {
        ITEM, TAG, MOD_ID;
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
                value -> Mode.valueOf(value.toUpperCase(java.util.Locale.ROOT)), Mode::getSerializedName);
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final class Filter {
        public static final Codec<Filter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Mode.CODEC.fieldOf("mode").forGetter(Filter::mode), Codec.STRING.fieldOf("value").forGetter(Filter::value),
                Codec.BOOL.optionalFieldOf("allow", true).forGetter(Filter::allow),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Filter::enabled)).apply(i, Filter::new));
        public static final StreamCodec<ByteBuf, Filter> STREAM_CODEC = StreamCodec.composite(
                Mode.STREAM_CODEC, Filter::mode, ByteBufCodecs.STRING_UTF8, Filter::value,
                ByteBufCodecs.BOOL, Filter::allow, ByteBufCodecs.BOOL, Filter::enabled, Filter::new);
        private final Mode mode;
        private final String value;
        private final boolean allow;
        private final boolean enabled;
        private final @Nullable Item item;
        private final @Nullable TagKey<Item> tag;

        public Filter(Mode mode, String value, boolean allow, boolean enabled) {
            this.mode = mode;
            this.value = value.trim();
            this.allow = allow;
            this.enabled = enabled;
            Identifier id = mode == Mode.MOD_ID ? null : Identifier.tryParse(this.value);
            item = mode == Mode.ITEM && id != null ? BuiltInRegistries.ITEM.getOptional(id).orElse(null) : null;
            tag = mode == Mode.TAG && id != null ? TagKey.create(Registries.ITEM, id) : null;
        }

        public Mode mode() { return mode; }
        public String value() { return value; }
        public boolean allow() { return allow; }
        public boolean enabled() { return enabled; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Filter filter && mode == filter.mode && value.equals(filter.value)
                    && allow == filter.allow && enabled == filter.enabled;
        }

        @Override
        public int hashCode() { return Objects.hash(mode, value, allow, enabled); }

        @Override
        public String toString() {
            return "Filter[mode=" + mode + ", value=" + value + ", allow=" + allow + ", enabled=" + enabled + "]";
        }

        public boolean valid() {
            if (mode == Mode.MOD_ID) return !value.isEmpty() && BuiltInRegistries.ITEM.stream()
                    .anyMatch(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value));
            return mode == Mode.ITEM ? item != null && item != Items.AIR
                    : tag != null && BuiltInRegistries.ITEM.get(tag).filter(items -> items.size() > 0).isPresent();
        }

        public boolean matches(Item item) {
            if (mode == Mode.MOD_ID) return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value);
            return mode == Mode.ITEM ? this.item == item : tag != null && item.builtInRegistryHolder().is(tag);
        }
    }

    public static final WaferSettings DEFAULT = new WaferSettings(List.of());
    // Older settings use different field names and load without filters.
    public static final Codec<WaferSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Filter.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(WaferSettings::rules)).apply(i, WaferSettings::new));
    public static final StreamCodec<ByteBuf, WaferSettings> STREAM_CODEC =
            Filter.STREAM_CODEC.apply(ByteBufCodecs.list()).map(WaferSettings::new, WaferSettings::rules);
    public WaferSettings { rules = List.copyOf(rules); }

    /** Matching Allow row, unfiltered last, or -1 when denied. Item components are ignored. */
    public int rank(Item item) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(item)) return rule.allow() ? i : -1;
        }
        return rules.isEmpty() ? Integer.MAX_VALUE : -1;
    }
    public boolean isDefault() { return equals(DEFAULT); }
}
