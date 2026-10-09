package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.storage.WaferSettings;
import java.util.List;
import java.util.function.ToLongFunction;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Stock Upgrade: how much more of each Output row a block may receive. A row counts everything it matches in the
 * block together; fluids and other mods' materials count in their own units (mB for fluids).
 */
final class PortStock {
    static final long NO_LIMIT = Long.MAX_VALUE;

    private PortStock() {}

    /** Per Output row, what the block may still take: its number less what it holds, or {@link #NO_LIMIT}. */
    static long[] itemsLeft(WaferSettings output, ResourceHandler<ItemResource> block) {
        return left(output, rule -> {
            long held = 0;
            for (int slot = 0; slot < block.size(); slot++) {
                ItemResource resource = block.getResource(slot);
                if (!resource.isEmpty() && rule.matches(resource.getItem())) held += block.getAmountAsLong(slot);
            }
            return held;
        });
    }

    static long[] fluidsLeft(WaferSettings output, ResourceHandler<FluidResource> block) {
        return left(output, rule -> {
            long held = 0;
            for (int slot = 0; slot < block.size(); slot++) {
                FluidResource resource = block.getResource(slot);
                if (!resource.isEmpty() && rule.matches(resource.getFluid())) held += block.getAmountAsLong(slot);
            }
            return held;
        });
    }

    static long[] materialsLeft(WaferSettings output, List<Machines.MaterialInlet> inlets) {
        return left(output, rule -> {
            long held = 0;
            for (var inlet : inlets) {
                for (int slot = 0; slot < inlet.handler().size(); slot++) {
                    Material material = Material.of(inlet.kind(), inlet.handler().getResource(slot));
                    if (material != null && rule.matches(material.holder(), material.key().id())) held += inlet.handler().getAmountAsLong(slot);
                }
            }
            return held;
        });
    }

    private static long[] left(WaferSettings output, ToLongFunction<WaferSettings.Filter> held) {
        var rules = output.rules();
        long[] left = new long[rules.size()];
        for (int i = 0; i < left.length; i++) {
            WaferSettings.Filter rule = rules.get(i);
            left[i] = rule.enabled() && rule.stock() > 0 ? Math.max(0, rule.stock() - held.applyAsLong(rule)) : NO_LIMIT;
        }
        return left;
    }

    /** What row {@code row} may still send; any row without a number (or no Stock Upgrade at all) has no limit. */
    static long of(long @Nullable [] left, int row) {
        return left == null || row < 0 || row >= left.length ? NO_LIMIT : left[row];
    }

    static void sent(long @Nullable [] left, int row, long amount) {
        if (left != null && row >= 0 && row < left.length && left[row] != NO_LIMIT) left[row] = Math.max(0, left[row] - amount);
    }
}
