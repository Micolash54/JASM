package dev.micolash.jasm.pool;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.storage.WaferSettings;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** What a Storage Port was set to: which items and fluids, which direction, and where it ranks among the network's storage. */
public record StorageSettings(WaferSettings filter, StorageAccess access, int priority) {
    public static final int MAX_PRIORITY = 999;
    public static final StorageSettings DEFAULT = new StorageSettings(WaferSettings.DEFAULT, StorageAccess.READ_WRITE, 0);
    public static final Codec<StorageSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            WaferSettings.CODEC.optionalFieldOf("filter", WaferSettings.DEFAULT).forGetter(StorageSettings::filter),
            StorageAccess.CODEC.optionalFieldOf("access", StorageAccess.READ_WRITE).forGetter(StorageSettings::access),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(StorageSettings::priority)).apply(i, StorageSettings::new));
    public static final StreamCodec<ByteBuf, StorageSettings> STREAM_CODEC = StreamCodec.composite(
            WaferSettings.STREAM_CODEC, StorageSettings::filter, StorageAccess.STREAM_CODEC, StorageSettings::access,
            ByteBufCodecs.VAR_INT, StorageSettings::priority, StorageSettings::new);

    public StorageSettings {
        priority = Math.clamp(priority, -MAX_PRIORITY, MAX_PRIORITY);
    }

    /** The filter lets the item through, for putting in and for showing and taking out. */
    public boolean passes(Item item) { return filter.rank(item) >= 0; }
    /** The filter names the item in an Allow row. */
    public boolean lists(Item item) { return filter.hasAllow() && filter.rank(item) >= 0; }
    /** The same for fluids. An item-only allow list lets no fluid through. */
    public boolean passes(Fluid fluid) { return filter.rank(fluid) >= 0; }
    public boolean lists(Fluid fluid) { return filter.hasAllow() && filter.rank(fluid) >= 0; }
    public boolean passes(Holder<?> holder, Identifier id) { return filter.rank(holder, id) >= 0; }
    public boolean lists(Holder<?> holder, Identifier id) { return filter.hasAllow() && filter.rank(holder, id) >= 0; }
}
