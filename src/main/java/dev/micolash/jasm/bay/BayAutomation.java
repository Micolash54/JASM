package dev.micolash.jasm.bay;

import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** What ports, hoppers and pipes see of a bay: the grid only, never the upgrades; in only where the bay takes things in. */
final class BayAutomation {
    private BayAutomation() {}

    static final class Items extends DelegatingResourceHandler<ItemResource> {
        private final boolean takesIn;

        Items(ResourceHandler<ItemResource> slots, boolean takesIn) {
            super(slots);
            this.takesIn = takesIn;
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return takesIn && index < BayBlockEntity.GRID ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            int moved = 0;
            for (int slot = 0; slot < BayBlockEntity.GRID && moved < amount; slot++) {
                moved += insert(slot, resource, amount - moved, transaction);
            }
            return moved;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index < BayBlockEntity.GRID ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            int moved = 0;
            for (int slot = 0; slot < BayBlockEntity.GRID && moved < amount; slot++) {
                moved += extract(slot, resource, amount - moved, transaction);
            }
            return moved;
        }
    }

    static final class Fluids extends DelegatingResourceHandler<FluidResource> {
        private final boolean takesIn;

        Fluids(ResourceHandler<FluidResource> tank, boolean takesIn) {
            super(tank);
            this.takesIn = takesIn;
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return takesIn ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(FluidResource resource, int amount, TransactionContext transaction) {
            return takesIn ? super.insert(resource, amount, transaction) : 0;
        }
    }
}
