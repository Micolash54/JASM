package dev.micolash.jasm.core;

import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** What one grid cell stands for: a kind of item, a kind of fluid, or one of another mod's materials. */
public sealed interface GridKey {
    record Item(ItemResource resource) implements GridKey {}

    record Fluid(FluidResource resource) implements GridKey {}

    record Material(MaterialKey key) implements GridKey {}

    /** The item, or null for anything else. */
    default @Nullable ItemResource item() {
        return this instanceof Item item ? item.resource() : null;
    }

    /** The fluid, or null for anything else. */
    default @Nullable FluidResource fluid() {
        return this instanceof Fluid fluid ? fluid.resource() : null;
    }

    /** The material, or null for an item or a fluid. */
    default @Nullable MaterialKey material() {
        return this instanceof Material material ? material.key() : null;
    }
}
