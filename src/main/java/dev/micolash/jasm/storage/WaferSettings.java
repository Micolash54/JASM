package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** One wafer's ordered filters. The first enabled match decides; an empty list accepts everything. */
public record WaferSettings(List<Filter> rules) {
    public enum Mode implements StringRepresentable {
        ITEM, TAG, MOD_ID;
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
                value -> Mode.valueOf(value.toUpperCase(java.util.Locale.ROOT)), Mode::getSerializedName);
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Filter(Mode mode, String value, boolean allow, boolean enabled) {
        public static final Codec<Filter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Mode.CODEC.fieldOf("mode").forGetter(Filter::mode), Codec.STRING.fieldOf("value").forGetter(Filter::value),
                Codec.BOOL.optionalFieldOf("allow", true).forGetter(Filter::allow),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Filter::enabled)).apply(i, Filter::new));
        public static final StreamCodec<ByteBuf, Filter> STREAM_CODEC = StreamCodec.composite(
                Mode.STREAM_CODEC, Filter::mode, ByteBufCodecs.STRING_UTF8, Filter::value,
                ByteBufCodecs.BOOL, Filter::allow, ByteBufCodecs.BOOL, Filter::enabled, Filter::new);
        public Filter { value = value.trim(); }

        public boolean valid() {
            if (mode == Mode.MOD_ID) return !value.isEmpty() && BuiltInRegistries.ITEM.stream()
                    .anyMatch(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value));
            Identifier id = Identifier.tryParse(value);
            if (id == null) return false;
            return mode == Mode.ITEM ? BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR).isPresent()
                    : BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id)).filter(tag -> tag.size() > 0).isPresent();
        }

        public boolean matches(Item item) {
            if (mode == Mode.MOD_ID) return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value);
            Identifier id = Identifier.tryParse(value);
            if (id == null) return false;
            return mode == Mode.ITEM ? BuiltInRegistries.ITEM.getKey(item).equals(id)
                    : item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, id));
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
