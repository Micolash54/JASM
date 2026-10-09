package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * A Transfer Port moving other mods' materials, the way it moves fluids: ask first, take, give, put back what did not
 * fit. Both ways return the shares used, so the port knows what is left of its allowance.
 */
final class TransferMaterials {
    private TransferMaterials() {}

    /**
     * Pushes pool materials the Output filter names into the block, as far as the allowance, the charge, the block and
     * {@code left} (per Output row, null without a Stock Upgrade) go.
     */
    static int out(List<Machines.MaterialInlet> inlets, DeckStorage.Checked storage, ItemStack deck, WaferSettings output, long @Nullable [] left,
            int shares, IntConsumer transferred) {
        var stacks = new ArrayList<>(storage.materialStacks().keySet());
        stacks.removeIf(m -> output.rank(m.holder(), m.key().id()) < 0);
        stacks.sort(Comparator.comparingInt(m -> output.rank(m.holder(), m.key().id())));
        int used = 0;
        for (Material material : stacks) {
            ResourceHandler<Resource> block = handler(inlets, material);
            if (block == null) continue;
            long most = Math.min((long) (shares - used) * FluidAmounts.PER_SHARE, DeckStorage.affordableFluid(deck));
            if (most <= 0) break;
            int row = output.rank(material.holder(), material.key().id());
            most = Math.min(most, PortStock.of(left, row));
            if (most <= 0) continue;
            int accepted;
            try (var tx = Transaction.openRoot()) {
                accepted = block.insert(material.resource(), (int) Math.min(most, Integer.MAX_VALUE), tx);   // only asking
            }
            if (accepted <= 0) continue;
            long taken = storage.withdrawMaterial(material, accepted);
            if (taken <= 0) continue;
            int inserted;
            try (var tx = Transaction.openRoot()) {
                inserted = block.insert(material.resource(), (int) taken, tx);
                if (inserted > 0) tx.commit();
            }
            if (inserted < taken) storage.restoreMaterial(material, taken - inserted);
            if (inserted > 0) {
                int moved = (int) FluidAmounts.shares(inserted);
                used += moved;
                transferred.accept(moved);
                PortStock.sent(left, row, inserted);
            }
        }
        return used;
    }

    /** Pulls the block's materials the Input filter allows into the pool, as far as there is room, the allowance and the charge go. */
    static int in(List<Machines.MaterialInlet> inlets, DeckStorage.Checked storage, ItemStack deck, WaferSettings input, int shares,
            IntConsumer transferred) {
        Map<Material, ResourceHandler<Resource>> held = new LinkedHashMap<>();
        for (var inlet : inlets) {
            for (int slot = 0; slot < inlet.handler().size(); slot++) {
                Material material = Material.of(inlet.kind(), inlet.handler().getResource(slot));
                if (material != null && inlet.handler().getAmountAsLong(slot) > 0) held.putIfAbsent(material, inlet.handler());
            }
        }
        var keys = new ArrayList<>(held.keySet());
        keys.removeIf(m -> input.rank(m.holder(), m.key().id()) < 0);
        keys.sort(Comparator.comparingInt(m -> input.rank(m.holder(), m.key().id())));
        int used = 0;
        for (Material material : keys) {
            ResourceHandler<Resource> block = held.get(material);
            long most = Math.min((long) (shares - used) * FluidAmounts.PER_SHARE, DeckStorage.affordableFluid(deck));
            if (most <= 0) break;
            long room = storage.roomMaterial(material, most);
            if (room <= 0) continue;
            int wanted;
            try (var tx = Transaction.openRoot()) {
                wanted = block.extract(material.resource(), (int) Math.min(room, Integer.MAX_VALUE), tx);   // only asking
            }
            if (wanted <= 0) continue;
            long stored = storage.depositMaterial(material, wanted);
            if (stored <= 0) continue;
            int taken;
            try (var tx = Transaction.openRoot()) {
                taken = block.extract(material.resource(), (int) stored, tx);
                if (taken > 0) tx.commit();
            }
            if (taken < stored) storage.takeBackMaterial(material, stored - taken);
            if (taken > 0) {
                int moved = (int) FluidAmounts.shares(taken);
                used += moved;
                transferred.accept(moved);
            }
        }
        return used;
    }

    /** The block's handler of the material's own kind, if it offers one. */
    private static @Nullable ResourceHandler<Resource> handler(List<Machines.MaterialInlet> inlets, Material material) {
        for (var inlet : inlets) if (inlet.kind() == material.kind()) return inlet.handler();
        return null;
    }
}
