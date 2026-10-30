package dev.micolash.jasm.storage;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** One wafer's ordered filters. The first enabled match decides; without an Allow row everything else is accepted. */
public record WaferSettings(List<Filter> rules) {
    public enum Mode implements StringRepresentable {
        MATERIAL,
        TAG,
        MOD_ID;
        // older saves have "item" and "fluid" rows, both are Material rows now
        public static final Codec<Mode> CODEC = Codec.STRING.comapFlatMap(name -> switch (name) {
            case "material", "item", "fluid" -> DataResult.success(MATERIAL);
            case "tag" -> DataResult.success(TAG);
            case "mod_id" -> DataResult.success(MOD_ID);
            default -> DataResult.error(() -> "Unknown filter mode " + name);
        }, Mode::getSerializedName);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
                value -> Mode.valueOf(value.toUpperCase(Locale.ROOT)), Mode::getSerializedName);
        @Override
        public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * The item data a Material row made from an item remembers, so it takes only that kind: one comb type, one
     * enchanted book. Wear, repair cost and a custom name never make another kind. A row without one takes every kind.
     * Data that no longer loads (its mod was removed) is kept as saved and written back unchanged; such a row takes
     * nothing, since no item in the game can carry it.
     */
    public record Variant(DataComponentPatch patch, @Nullable Dynamic<?> unread) {
        public static final Codec<Variant> CODEC = new Codec<>() {
            @Override
            public <T> DataResult<Pair<Variant, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<DataComponentPatch> parsed = DataComponentPatch.CODEC.parse(ops, input);
                Variant variant = parsed.result().map(Variant::new).orElseGet(() -> {
                    Jasm.LOGGER.warn("Kept unreadable filter item data as saved: {}", parsed.error().map(e -> e.message()).orElse("?"));
                    return new Variant(DataComponentPatch.EMPTY, new Dynamic<>(ops, input));
                });
                return DataResult.success(Pair.of(variant, ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(Variant variant, DynamicOps<T> ops, T prefix) {
                if (variant.unread() != null) return DataResult.success(variant.unread().convert(ops).getValue());
                DataResult<T> encoded = DataComponentPatch.CODEC.encode(variant.patch(), ops, prefix);
                if (encoded.result().isPresent()) return encoded;
                // One bad row never stops the save. The stand-in loads as unreadable, so the row still takes no other kind.
                String error = encoded.error().map(e -> e.message()).orElse("?");
                Jasm.LOGGER.error("Could not save filter item data {}: {}. Saved a placeholder instead", variant.patch(), error);
                return DataResult.success(ops.createMap(Map.of(ops.createString("unsaveable"), ops.createString(String.valueOf(variant.patch())),
                        ops.createString("error"), ops.createString(error))));
            }
        };
        public static final StreamCodec<RegistryFriendlyByteBuf, Variant> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Variant decode(RegistryFriendlyByteBuf buf) {
                return buf.readBoolean() ? new Variant(DataComponentPatch.EMPTY, new Dynamic<>(NbtOps.INSTANCE, ByteBufCodecs.TAG.decode(buf)))
                        : new Variant(DataComponentPatch.STREAM_CODEC.decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, Variant variant) {
                buf.writeBoolean(variant.unread() != null);
                if (variant.unread() != null) ByteBufCodecs.TAG.encode(buf, variant.unread().convert(NbtOps.INSTANCE).getValue());
                else DataComponentPatch.STREAM_CODEC.encode(buf, variant.patch());
            }
        };

        public Variant(DataComponentPatch patch) { this(patch.forget(Variant::ignored), null); }

        /** The kind of {@code stack}, without what never tells kinds apart. */
        public static Variant of(ItemStack stack) { return new Variant(stack.getComponentsPatch()); }

        // transient data is never saved, so a saved row can't hold it either
        private static boolean ignored(DataComponentType<?> type) {
            return type == DataComponents.DAMAGE || type == DataComponents.REPAIR_COST || type == DataComponents.CUSTOM_NAME || type.isTransient();
        }

        /** Same kind: every remembered piece of data is there with the same value, and nothing else is. */
        boolean matches(DataComponentPatch other) {
            if (unread != null) return false;
            int seen = 0;
            for (var entry : other.entrySet()) {
                if (ignored(entry.getKey())) continue;
                if (!entry.getValue().equals(patch.getPatch(entry.getKey()))) return false;
                seen++;
            }
            return seen == patch.size();
        }
    }

    public static final class Filter {
        public static final int MAX_STOCK = 999_999;
        public static final Codec<Filter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Mode.CODEC.fieldOf("mode").forGetter(Filter::mode), Codec.STRING.fieldOf("value").forGetter(Filter::value),
                Codec.BOOL.optionalFieldOf("allow", true).forGetter(Filter::allow),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Filter::enabled),
                Codec.BOOL.optionalFieldOf("void", false).forGetter(Filter::voidExcess),
                Codec.INT.optionalFieldOf("stock", 0).forGetter(Filter::stock),
                // rows from older saves have no item data, so they still take every kind
                Variant.CODEC.optionalFieldOf("components").forGetter(filter -> Optional.ofNullable(filter.variant())))
                .apply(i, (mode, value, allow, enabled, voidExcess, stock, variant) ->
                        new Filter(mode, value, allow, enabled, voidExcess, stock, variant.orElse(null))));
        public static final StreamCodec<RegistryFriendlyByteBuf, Filter> STREAM_CODEC = StreamCodec.composite(
                Mode.STREAM_CODEC, Filter::mode, ByteBufCodecs.STRING_UTF8, Filter::value,
                ByteBufCodecs.BOOL, Filter::allow, ByteBufCodecs.BOOL, Filter::enabled,
                ByteBufCodecs.BOOL, Filter::voidExcess, ByteBufCodecs.VAR_INT, Filter::stock,
                ByteBufCodecs.optional(Variant.STREAM_CODEC), filter -> Optional.ofNullable(filter.variant()),
                (mode, value, allow, enabled, voidExcess, stock, variant) -> new Filter(mode, value, allow, enabled, voidExcess, stock, variant.orElse(null)));
        private final Mode mode;
        private final String value;
        private final boolean allow;
        private final boolean enabled;
        private final boolean voidExcess;
        private final int stock;
        private final @Nullable Identifier id;
        private final @Nullable Item item;
        private final @Nullable TagKey<Item> tag;
        private final @Nullable Fluid fluid;
        private final @Nullable TagKey<Fluid> fluidTag;
        private final boolean known;
        private final @Nullable Variant variant;
        // one tag key per registry a material came from, made on first use
        private final Map<ResourceKey<?>, TagKey<?>> tags = new ConcurrentHashMap<>();

        public Filter(Mode mode, String value, boolean allow, boolean enabled) {
            this(mode, value, allow, enabled, false);
        }

        public Filter(Mode mode, String value, boolean allow, boolean enabled, boolean voidExcess) {
            this(mode, value, allow, enabled, voidExcess, 0);
        }

        public Filter(Mode mode, String value, boolean allow, boolean enabled, boolean voidExcess, int stock) {
            this(mode, value, allow, enabled, voidExcess, stock, null);
        }

        public Filter(Mode mode, String value, boolean allow, boolean enabled, boolean voidExcess, int stock, @Nullable Variant variant) {
            this.mode = mode;
            this.value = value.trim();
            this.allow = allow;
            this.enabled = enabled;
            // Only an Allow row can destroy leftovers.
            this.voidExcess = voidExcess && allow;
            // Only an Allow row keeps a stock; 0 means no limit.
            this.stock = allow ? Math.clamp(stock, 0, MAX_STOCK) : 0;
            id = mode == Mode.MOD_ID ? null : Identifier.tryParse(this.value);
            item = mode == Mode.MATERIAL && id != null ? BuiltInRegistries.ITEM.getOptional(id).orElse(null) : null;
            fluid = mode == Mode.MATERIAL && id != null ? BuiltInRegistries.FLUID.getOptional(id).orElse(null) : null;
            tag = mode == Mode.TAG && id != null ? TagKey.create(Registries.ITEM, id) : null;
            fluidTag = mode == Mode.TAG && id != null ? TagKey.create(Registries.FLUID, id) : null;
            known = mode == Mode.MATERIAL && id != null && registered(id);
            this.variant = mode == Mode.MATERIAL ? variant : null;
        }

        public Mode mode() { return mode; }
        public String value() { return value; }
        public boolean allow() { return allow; }
        public boolean enabled() { return enabled; }
        public boolean voidExcess() { return voidExcess; }
        /** How many of what this row matches an Output Port with a Stock Upgrade keeps in the block it feeds; 0 for no limit. */
        public int stock() { return stock; }
        public Filter withStock(int stock) { return new Filter(mode, value, allow, enabled, voidExcess, stock, variant); }
        /** The kind of item a row made from an item takes; null when it takes every kind. */
        public @Nullable Variant variant() { return variant; }
        /** The exact kind a row made from an item takes, to show on the row; empty when it takes every kind. */
        public ItemStack variantStack() {
            return variant == null || item == null || variant.unread() != null ? ItemStack.EMPTY
                    : new ItemStack(item.builtInRegistryHolder(), 1, variant.patch());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Filter filter && mode == filter.mode && value.equals(filter.value)
                    && allow == filter.allow && enabled == filter.enabled && voidExcess == filter.voidExcess && stock == filter.stock
                    && Objects.equals(variant, filter.variant);
        }

        @Override
        public int hashCode() { return Objects.hash(mode, value, allow, enabled, voidExcess, stock, variant); }

        @Override
        public String toString() {
            return "Filter[mode=" + mode + ", value=" + value + ", allow=" + allow + ", enabled=" + enabled + ", void=" + voidExcess + ", stock=" + stock
                    + (variant == null ? "" : ", components=" + (variant.unread() != null ? "unreadable" : variant.patch())) + "]";
        }

        // loose on purpose: any registry that knows the ID counts, so a chemical can be named too
        private static boolean registered(Identifier id) {
            // air and the empty fluid also sit in registries of block types and the like, they are still not choices
            if (id.equals(BuiltInRegistries.ITEM.getDefaultKey()) || id.equals(BuiltInRegistries.FLUID.getDefaultKey())) return false;
            for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
                if (registry.containsKey(id) && !(registry instanceof DefaultedRegistry<?> defaulted && defaulted.getDefaultKey().equals(id)))
                    return true;
            }
            return false;
        }

        private static boolean tagged(Identifier id) {
            for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
                if (hasTag(registry, id)) return true;
            }
            return false;
        }

        private static <T> boolean hasTag(Registry<T> registry, Identifier id) {
            return registry.get(TagKey.create(registry.key(), id)).filter(set -> set.size() > 0).isPresent();
        }

        public boolean valid() {
            return switch (mode) {
                case MATERIAL -> known;
                case TAG -> id != null && tagged(id);
                case MOD_ID -> !value.isEmpty() && (BuiltInRegistries.ITEM.stream()
                        .anyMatch(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value))
                        || BuiltInRegistries.FLUID.stream()
                                .anyMatch(fluid -> BuiltInRegistries.FLUID.getKey(fluid).getNamespace().equals(value)));
            };
        }

        /** Every filter in the mod decides about an item here. */
        public boolean matches(ItemStack stack) {
            return variant != null ? item == stack.getItem() && variant.matches(stack.getComponentsPatch()) : matchesType(stack.getItem());
        }

        public boolean matches(ItemResource key) {
            return variant != null ? item == key.getItem() && variant.matches(key.getComponentsPatch()) : matchesType(key.getItem());
        }

        private boolean matchesType(Item item) {
            return switch (mode) {
                case MATERIAL -> this.item == item;
                case TAG -> tag != null && item.builtInRegistryHolder().is(tag);
                case MOD_ID -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(value);
            };
        }

        public boolean matches(Fluid fluid) {
            return switch (mode) {
                case MATERIAL -> this.fluid == fluid;
                case TAG -> fluidTag != null && fluid.builtInRegistryHolder().is(fluidTag);
                case MOD_ID -> BuiltInRegistries.FLUID.getKey(fluid).getNamespace().equals(value);
            };
        }

        /** A modded material, by its registry entry and ID. A row made from an item only ever takes that item. */
        public boolean matches(Holder<?> holder, Identifier materialId) {
            return switch (mode) {
                case MATERIAL -> variant == null && materialId.equals(id);
                case TAG -> id != null && holder.unwrapKey().map(key -> inTag(holder, key.registryKey())).orElse(false);
                case MOD_ID -> materialId.getNamespace().equals(value);
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private boolean inTag(Holder holder, ResourceKey registry) {
            TagKey tagKey = tags.computeIfAbsent(registry, r -> TagKey.create((ResourceKey) r, id));
            return holder.is(tagKey);
        }
    }

    public static final WaferSettings DEFAULT = new WaferSettings(List.of());
    // Older settings use different field names and load without filters.
    public static final Codec<WaferSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Filter.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(WaferSettings::rules)).apply(i, WaferSettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaferSettings> STREAM_CODEC = Filter.STREAM_CODEC.apply(ByteBufCodecs.list()).map(WaferSettings::new,
            WaferSettings::rules);
    public WaferSettings {
        rules = List.copyOf(rules);
    }

    /** Only an enabled Allow row makes a wafer exclusive; with just Deny rows it still takes everything else. */
    public boolean hasAllow() {
        return rules.stream().anyMatch(rule -> rule.enabled() && rule.allow());
    }
    /** Matching Allow row, unmatched items last, or -1 when denied. */
    public int rank(ItemStack stack) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(stack)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    public int rank(ItemResource key) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(key)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    /** The same decision for a fluid. */
    public int rank(Fluid fluid) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(fluid)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    /** True when the first enabled row that matches is an Allow row with Void on: leftovers of this item are destroyed. */
    public boolean voidsExcess(ItemResource key) {
        for (Filter rule : rules) {
            if (rule.enabled() && rule.matches(key)) return rule.allow() && rule.voidExcess();
        }
        return false;
    }
    public boolean voidsExcess(Fluid fluid) {
        for (Filter rule : rules) {
            if (rule.enabled() && rule.matches(fluid)) return rule.allow() && rule.voidExcess();
        }
        return false;
    }
    /** The same decision for a modded material. */
    public int rank(Holder<?> holder, Identifier id) {
        for (int i = 0; i < rules.size(); i++) {
            Filter rule = rules.get(i);
            if (rule.enabled() && rule.matches(holder, id)) return rule.allow() ? i : -1;
        }
        return hasAllow() ? -1 : Integer.MAX_VALUE;
    }
    public boolean voidsExcess(Holder<?> holder, Identifier id) {
        for (Filter rule : rules) {
            if (rule.enabled() && rule.matches(holder, id)) return rule.allow() && rule.voidExcess();
        }
        return false;
    }
    /** True when any enabled row would destroy leftovers. */
    public boolean hasVoid() {
        return rules.stream().anyMatch(rule -> rule.enabled() && rule.voidExcess());
    }
    public boolean isDefault() { return equals(DEFAULT); }
}
