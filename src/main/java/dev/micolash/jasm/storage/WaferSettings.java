package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/** One wafer's ordered filters. The first enabled match decides; without an Allow row everything else is accepted. */
public record WaferSettings(List<Filter> rules) {
    public enum Mode implements StringRepresentable {
        ITEM,
        TAG,
        MOD_ID,
        FLUID;
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
                value -> Mode.valueOf(value.toUpperCase(Locale.ROOT)), Mode::getSerializedName);
        @Override
        public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final class Filter {
        public static final Codec<Filter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Mode.CODEC.fieldOf("mode").forGetter(Filter::mode), Codec.STRING.fieldOf("value").forGetter(Filter::value),
                Codec.BOOL.optionalFieldOf("allow", true).forGetter(Filter::allow),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Filter::enabled),
                Codec.BOOL.optionalFieldOf("void", false).forGetter(Filter::voidExcess)).apply(i, Filter::new));
        public static final StreamCodec<ByteBuf, Filter> STREAM_CODEC = StreamCodec.composite(
                Mode.STREAM_CODEC, Filter::mode, ByteBufCodecs.STRING_UTF8, Filter::value,
                ByteBufCodecs.BOOL, Filter::allow, ByteBufCodecs.BOOL, Filter::enabled,
                ByteBufCodecs.BOOL, Filter::voidExcess, Filter::new);
        private final Mode mode;
        private final String value;
        private final boolean allow;
        private final boolean enabled;
        private final boolean voidExcess;
        private final @Nullable Item item;
        private final @Nullable TagKey<Item> tag;
        private final @Nullable Fluid fluid;
        private final @Nullable TagKey<Fluid> fluidTag;

        public Filter(Mode mode, String value, boolean allow, boolean enabled) {
            this(mode, value, allow, enabled, false);
        }

        public Filter(Mode mode, String value, boolean allow, boolean enabled, boolean voidExcess) {
            this.mode = mode;
            this.value = value.trim();
            this.allow = allow;
            this.enabled = enabled;
            // Only an Allow row can destroy leftovers.
            this.voidExcess = voidExcess && allow;
            Identifier id = mode == Mode.MOD_ID ? null : Identifier.tryParse(this.value);
            item = mode == Mode.ITEM && id != null ? BuiltInRegistries.ITEM.getOptional(id).orElse(null) : null;
            tag = mode == Mode.TAG && id != null ? TagKey.create(Registries.ITEM, id) : null;
            fluid = mode == Mode.FLUID && id != null ? BuiltInRegistries.FLUID.getOptional(id).orElse(null) : null;
            fluidTag = mode == Mode.TAG && id != null ? TagKey.create(Registries.FLUID, id) : null;
        }

        public Mode mode() { return mode; }
        public String value() { return value; }
        public boolean allow() { return allow; }
        public boolean enabled() { return enabled; }
        public boolean voidExcess() { return voidExcess; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Filter filter && mode == filter.mode && value.equals(filter.value)
                    && allow == filter.allow && enabled == filter.enabled && voidExcess == filter.voidExcess;
        }

        @Override
        public int hashCode() { return Objects.hash(mode, value, allow, enabled, voidExcess); }

        @Override
        public String toString() {
            return "Filter[mode=" + mode + ", value=" + value + ", allow=" + allow + ", enabled=" + enabled + ", void=" + voidExcess + "]";
        }

        public boolean valid() {
            if (mode == Mode.MOD_ID)
                return !value.isEmpty() && (BuiltInRegistries.ITEM.stream()
                        .anyMatch(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value))
                        || BuiltInRegistries.FLUID.stream()
                                .anyMatch(fluid -> BuiltInRegistries.FLUID.getKey(fluid).getNamespace().equals(value)));
            if (mode == Mode.FLUID) return fluid != null && fluid != Fluids.EMPTY;
            return mode == Mode.ITEM
                    ? item != null && item != Items.AIR
                    : tag != null && BuiltInRegistries.ITEM.get(tag).filter(items -> items.size() > 0).isPresent()
                            || fluidTag != null && BuiltInRegistries.FLUID.get(fluidTag).filter(fluids -> fluids.size() > 0).isPresent();
        }

        public boolean matches(Item item) {
            if (mode == Mode.FLUID) return false;
            if (mode == Mode.MOD_ID) return BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value);
            return mode == Mode.ITEM ? this.item == item : tag != null && item.builtInRegistryHolder().is(tag);
        }

        public boolean matches(Fluid fluid) {
            if (mode == Mode.ITEM) return false;
            if (mode == Mode.MOD_ID) return BuiltInRegistries.FLUID.getKey(fluid).getNamespace().equals(value);
            return mode == Mode.FLUID ? this.fluid == fluid : fluidTag != null && fluid.builtInRegistryHolder().is(fluidTag);
        }
    }

    public static final WaferSettings DEFAULT = new WaferSettings(List.of());
    // Older settings use different field names and load without filters.
    public static final Codec<WaferSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Filter.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(WaferSettings::rules)).apply(i, WaferSettings::new));
    public static final StreamCodec<ByteBuf, WaferSettings> STREAM_CODEC = Filter.STREAM_CODEC.apply(ByteBufCodecs.list()).map(WaferSettings::new,
            WaferSettings::rules);
    public WaferSettings {
        rules = List.copyOf(rules);
    }

    /** Only an enabled Allow row makes a wafer exclusive; with just Deny rows it still takes everything else. */
    public boolean hasAllow() {
        return rules.stream().anyMatch(rule -> rule.enabled() && rule.allow());
    }
    /** Matching Allow row, unmatched items last, or -1 when denied. Item components are ignored. */
    public int rank(Item item) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(item)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    /** The same decision for a fluid: Fluid rows, fluid tags and mods; Item rows never match. */
    public int rank(Fluid fluid) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(fluid)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    /** True when the first enabled row that matches is an Allow row with Void on: leftovers of this item are destroyed. */
    public boolean voidsExcess(Item item) {
        for (Filter rule : rules) {
            if (rule.enabled() && rule.matches(item)) return rule.allow() && rule.voidExcess();
        }
        return false;
    }
    /** The same decision for a fluid: Fluid rows, fluid tags and mods; Item rows never match. */
    public boolean voidsExcess(Fluid fluid) {
        for (Filter rule : rules) {
            if (rule.enabled() && rule.matches(fluid)) return rule.allow() && rule.voidExcess();
        }
        return false;
    }
    /** True when any enabled row would destroy leftovers. */
    public boolean hasVoid() {
        return rules.stream().anyMatch(rule -> rule.enabled() && rule.voidExcess());
    }
    public boolean isDefault() { return equals(DEFAULT); }
}
