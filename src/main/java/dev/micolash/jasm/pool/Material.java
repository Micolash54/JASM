package dev.micolash.jasm.pool;

import dev.micolash.jasm.core.MaterialKey;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.RegisteredResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/** One kind of thing in another mod's container: the capability it was found under, and the real resource. */
public record Material(BlockCapability<ResourceHandler<Resource>, @Nullable Direction> kind, Resource resource) {
    public static @Nullable Material of(BlockCapability<ResourceHandler<Resource>, @Nullable Direction> kind, Resource resource) {
        return MaterialKey.of(resource) == null ? null : new Material(kind, resource);
    }

    public MaterialKey key() {
        return MaterialKey.of(resource);
    }

    public Holder<?> holder() {
        return ((RegisteredResource<?>) resource).typeHolder();
    }
}
