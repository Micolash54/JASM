package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * How a Deck routes items to one wafer: a priority (higher fills first, lower empties first) and a filter of items
 * the wafer is for. With {@code only}, the wafer takes nothing that isn't on its filter. Filters match the item and
 * ignore data, and are kept by id, so an item from a removed mod stays on the list.
 */
public record WaferSettings(int priority, boolean only, List<Identifier> filter) {
    public static final int MIN_PRIORITY = -9;
    public static final int MAX_PRIORITY = 9;
    public static final int FILTER_SLOTS = 9;
    public static final WaferSettings DEFAULT = new WaferSettings(0, false, List.of());

    public static final Codec<WaferSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(WaferSettings::priority),
                    Codec.BOOL.optionalFieldOf("only", false).forGetter(WaferSettings::only),
                    Identifier.CODEC.listOf().optionalFieldOf("filter", List.of()).forGetter(WaferSettings::filter))
            .apply(i, WaferSettings::new));

    public static final StreamCodec<ByteBuf, WaferSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WaferSettings::priority,
            ByteBufCodecs.BOOL, WaferSettings::only,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(FILTER_SLOTS)), WaferSettings::filter,
            WaferSettings::new);

    /** Always in range: priority clamped, filter without duplicates or air, at most {@link #FILTER_SLOTS} long. */
    public WaferSettings {
        priority = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
        List<Identifier> clean = new ArrayList<>(new LinkedHashSet<>(filter));
        clean.removeIf(id -> id.equals(BuiltInRegistries.ITEM.getKey(Items.AIR)));
        filter = List.copyOf(clean.subList(0, Math.min(FILTER_SLOTS, clean.size())));
    }

    public boolean lists(Item item) {
        return filter.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }
}
