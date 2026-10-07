package dev.micolash.jasm.core;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.RegisteredResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/** Something from another mod's containers that isn't an item or a fluid, by registry and ID. Extra data is ignored. */
public record MaterialKey(Identifier registry, Identifier id) {
    public static final StreamCodec<ByteBuf, MaterialKey> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, MaterialKey::registry, Identifier.STREAM_CODEC, MaterialKey::id, MaterialKey::new);

    // items and fluids keep their own keys, and a resource with no registry name can't be listed or filtered
    public static @Nullable MaterialKey of(Resource resource) {
        if (resource.isEmpty() || resource instanceof ItemResource || resource instanceof FluidResource
                || !(resource instanceof RegisteredResource<?> registered)) return null;
        return registered.typeHolder().unwrapKey().map(key -> new MaterialKey(key.registry(), key.identifier())).orElse(null);
    }

    /** chemical.mekanism.hydrogen and so on, built the vanilla way. */
    public String translationKey() {
        return Util.makeDescriptionId(registry.getPath(), id);
    }
}
