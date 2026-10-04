package dev.micolash.jasm.core;

import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** What one grid cell stands for: a kind of item, or a kind of fluid. */
public sealed interface GridKey {
    record Item(ItemResource resource) implements GridKey {}

    record Fluid(FluidResource resource) implements GridKey {}

    /** The item, or null for a fluid. */
    default @Nullable ItemResource item() {
        return this instanceof Item item ? item.resource() : null;
    }

    /** The fluid, or null for an item. */
    default @Nullable FluidResource fluid() {
        return this instanceof Fluid fluid ? fluid.resource() : null;
    }
}
