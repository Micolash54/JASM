package dev.micolash.jasm.pool;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.core.PoolRouter;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.RegisteredResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One block seen through one Storage Port: what it holds as items and as fluids. Both capabilities are cached and
 * refresh themselves when the block changes. A move that comes back into the same store while it is busy does nothing,
 * and neither does one asked for while another transaction is open (it could not be undone with that one).
 */
public final class PoolStore implements PoolRouter.Unit<ItemResource> {
    private final StoragePortBlockEntity port;
    private final FluidSide fluidSide = new FluidSide();
    private final MaterialSide materialSide = new MaterialSide();
    private @Nullable BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> items;
    private @Nullable BlockCapabilityCache<ResourceHandler<FluidResource>, Direction> fluids;
    private @Nullable List<BlockCapabilityCache<ResourceHandler<Resource>, @Nullable Direction>> materials;
    private boolean busy;

    PoolStore(StoragePortBlockEntity port) { this.port = port; }

    public StorageSettings settings() { return port.settings(); }
    public BlockPos chestPos() { return port.chestPos(); }
    /** The fluid side of this store, for ordering fluids. */
    public PoolRouter.Unit<FluidResource> fluidUnit() { return fluidSide; }
    /** The same for other mods' materials. */
    public PoolRouter.Unit<Material> materialUnit() { return materialSide; }

    @Override
    public int priority() { return settings().priority(); }
    @Override
    public boolean wafer() { return false; }
    @Override
    public boolean canRead() { return settings().access().canRead() && port.storeActive(); }
    @Override
    public boolean canWrite() { return settings().access().canWrite() && port.storeActive(); }
    @Override
    public boolean prefers(ItemResource key) { return settings().lists(key.getItem()) || count(key) > 0; }
    public boolean passes(ItemResource key) { return settings().passes(key.getItem()); }
    public boolean passes(FluidResource key) { return settings().passes(key.getFluid()); }

    private final class FluidSide implements PoolRouter.Unit<FluidResource> {
        @Override
        public int priority() { return PoolStore.this.priority(); }
        @Override
        public boolean wafer() { return false; }
        @Override
        public boolean canRead() { return PoolStore.this.canRead(); }
        @Override
        public boolean canWrite() { return PoolStore.this.canWrite(); }
        @Override
        public boolean prefers(FluidResource key) { return settings().lists(key.getFluid()) || countFluid(key) > 0; }
    }

    private final class MaterialSide implements PoolRouter.Unit<Material> {
        @Override
        public int priority() { return PoolStore.this.priority(); }
        @Override
        public boolean wafer() { return false; }
        @Override
        public boolean canRead() { return PoolStore.this.canRead(); }
        @Override
        public boolean canWrite() { return PoolStore.this.canWrite(); }
        @Override
        public boolean prefers(Material key) { return settings().lists(key.holder(), key.key().id()) || countMaterial(key) > 0; }
    }

    /** The level, if the port may use its block right now: loaded, active, and not a crafting block, Archive or cable. */
    private @Nullable ServerLevel usableLevel() {
        if (!(port.getLevel() instanceof ServerLevel level) || !port.storeActive()) return null;
        var entity = level.getBlockEntity(chestPos());
        return entity instanceof MachineBlockEntity || entity instanceof ArchiveBlockEntity || entity instanceof DataCableBlockEntity
                ? null : level;
    }

    private @Nullable ResourceHandler<ItemResource> itemHandler() {
        ServerLevel level = usableLevel();
        if (level == null) return null;
        if (items == null) items = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, chestPos(), port.face().getOpposite());
        return items.getCapability();
    }

    private @Nullable ResourceHandler<FluidResource> fluidHandler() {
        ServerLevel level = usableLevel();
        if (level == null) return null;
        if (fluids == null) fluids = BlockCapabilityCache.create(Capabilities.Fluid.BLOCK, level, chestPos(), port.face().getOpposite());
        return fluids.getCapability();
    }

    private @Nullable List<BlockCapabilityCache<ResourceHandler<Resource>, @Nullable Direction>> materialCaches() {
        ServerLevel level = usableLevel();
        if (level == null) return null;
        if (materials == null) {
            List<BlockCapabilityCache<ResourceHandler<Resource>, @Nullable Direction>> caches = new ArrayList<>();
            for (var kind : MaterialKinds.blocks()) caches.add(BlockCapabilityCache.create(kind, level, chestPos(), port.face().getOpposite()));
            materials = List.copyOf(caches);
        }
        return materials;
    }

    private static int slots(int size) {
        return Math.min(size, Tuning.POOL_SCAN_SLOTS);
    }

    // --- items ---

    public long count(ItemResource key) {
        var handler = itemHandler();
        if (handler == null || !settings().access().canRead() || !passes(key)) return 0;
        long total = 0;
        for (int slot = 0; slot < slots(handler.size()); slot++) if (key.equals(handler.getResource(slot))) total += handler.getAmountAsLong(slot);
        return total;
    }

    /** Adds what the block holds and the filter lets through to {@code into}. Reads at most the configured slots. */
    public void scan(Map<ItemResource, Long> into) {
        var handler = itemHandler();
        if (handler == null || !settings().access().canRead()) return;
        for (int slot = 0; slot < slots(handler.size()); slot++) {
            ItemResource held = handler.getResource(slot);
            if (!held.isEmpty() && passes(held)) into.merge(held, handler.getAmountAsLong(slot), Long::sum);
        }
    }

    /** How many of {@code key}, up to {@code most}, the block would take. Changes nothing. */
    public long room(ItemResource key, long most) {
        if (!canWrite() || !passes(key) || Transaction.getCurrentOpenedTransaction() != null) return 0;
        try (Transaction tx = Transaction.openRoot()) {
            return insert(key, most, tx);
        }
    }

    public long insert(ItemResource key, long amount, TransactionContext tx) {
        var handler = itemHandler();
        if (handler == null || amount <= 0 || !canWrite() || !passes(key)) return 0;
        return handler.insert(key, (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long extract(ItemResource key, long amount, TransactionContext tx) {
        var handler = itemHandler();
        if (handler == null || amount <= 0 || !canRead() || !passes(key)) return 0;
        return handler.extract(key, (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long insertNow(ItemResource key, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = insert(key, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }

    public long extractNow(ItemResource key, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = extract(key, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }

    // --- fluids, in millibuckets ---

    public long countFluid(FluidResource key) {
        var handler = fluidHandler();
        if (handler == null || !settings().access().canRead() || !passes(key)) return 0;
        long total = 0;
        for (int slot = 0; slot < slots(handler.size()); slot++) if (key.equals(handler.getResource(slot))) total += handler.getAmountAsLong(slot);
        return total;
    }

    public void scanFluids(Map<FluidResource, Long> into) {
        var handler = fluidHandler();
        if (handler == null || !settings().access().canRead()) return;
        for (int slot = 0; slot < slots(handler.size()); slot++) {
            FluidResource held = handler.getResource(slot);
            if (!held.isEmpty() && passes(held)) into.merge(held, handler.getAmountAsLong(slot), Long::sum);
        }
    }

    // --- other mods' materials, listed by ID ---

    private record Seen(BlockCapability<ResourceHandler<Resource>, @Nullable Direction> kind, ResourceHandler<Resource> handler) {}

    /** The handlers the block offers, one per distinct handler instance, under the first kind that showed it. */
    private List<Seen> materialHandlers() {
        var caches = materialCaches();
        if (caches == null || caches.isEmpty()) return List.of();
        var kinds = MaterialKinds.blocks();
        List<Seen> seen = new ArrayList<>(2);
        for (int i = 0; i < caches.size(); i++) {
            ResourceHandler<Resource> handler = caches.get(i).getCapability();
            // some blocks hand out one handler under two kinds
            if (handler == null || seen.stream().anyMatch(known -> known.handler() == handler)) continue;
            seen.add(new Seen(kinds.get(i), handler));
        }
        return seen;
    }

    public void scanMaterials(Map<MaterialKey, Long> into) {
        if (!settings().access().canRead()) return;
        for (Seen seen : materialHandlers()) {
            ResourceHandler<Resource> handler = seen.handler();
            for (int slot = 0; slot < slots(handler.size()); slot++) {
                Resource held = handler.getResource(slot);
                MaterialKey key = MaterialKey.of(held);
                long amount = key == null ? 0 : handler.getAmountAsLong(slot);
                if (amount > 0 && settings().passes(((RegisteredResource<?>) held).typeHolder(), key.id()))
                    into.merge(key, amount, PoolStore::add);
            }
        }
    }

    // --- materials, moved by the real resource ---

    private @Nullable ResourceHandler<Resource> handlerOf(Material material) {
        for (Seen seen : materialHandlers()) if (seen.kind() == material.kind()) return seen.handler();
        return null;
    }

    private boolean passes(Material material) {
        return settings().passes(material.holder(), material.key().id());
    }

    /** What the block holds right now, by exact resource. Reads at most the configured slots of each handler. */
    public void scanMaterialStacks(Map<Material, Long> into) {
        if (!settings().access().canRead()) return;
        for (Seen seen : materialHandlers()) {
            ResourceHandler<Resource> handler = seen.handler();
            for (int slot = 0; slot < slots(handler.size()); slot++) {
                Material held = Material.of(seen.kind(), handler.getResource(slot));
                long amount = held == null ? 0 : handler.getAmountAsLong(slot);
                if (amount > 0 && passes(held)) into.merge(held, amount, PoolStore::add);
            }
        }
    }

    public long countMaterial(Material material) {
        var handler = handlerOf(material);
        if (handler == null || !settings().access().canRead() || !passes(material)) return 0;
        long total = 0;
        for (int slot = 0; slot < slots(handler.size()); slot++) {
            if (material.resource().equals(handler.getResource(slot))) total = add(total, handler.getAmountAsLong(slot));
        }
        return total;
    }

    /** How much of {@code material}, up to {@code most}, the block would take. Changes nothing. */
    public long roomMaterial(Material material, long most) {
        if (!canWrite() || !passes(material) || Transaction.getCurrentOpenedTransaction() != null) return 0;
        try (Transaction tx = Transaction.openRoot()) {
            return insertMaterial(material, most, tx);
        }
    }

    public long insertMaterial(Material material, long amount, TransactionContext tx) {
        var handler = handlerOf(material);
        if (handler == null || amount <= 0 || !canWrite() || !passes(material)) return 0;
        return handler.insert(material.resource(), (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long extractMaterial(Material material, long amount, TransactionContext tx) {
        var handler = handlerOf(material);
        if (handler == null || amount <= 0 || !canRead() || !passes(material)) return 0;
        return handler.extract(material.resource(), (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long insertMaterialNow(Material material, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = insertMaterial(material, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }

    public long extractMaterialNow(Material material, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = extractMaterial(material, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }

    // creative tanks report huge numbers, two of them must not wrap round
    private static long add(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** How many millibuckets of {@code key}, up to {@code most}, the block would take. A cauldron says 0 below a bucket. */
    public long roomFluid(FluidResource key, long most) {
        if (!canWrite() || !passes(key) || Transaction.getCurrentOpenedTransaction() != null) return 0;
        try (Transaction tx = Transaction.openRoot()) {
            return insertFluid(key, most, tx);
        }
    }

    public long insertFluid(FluidResource key, long amount, TransactionContext tx) {
        var handler = fluidHandler();
        if (handler == null || amount <= 0 || !canWrite() || !passes(key)) return 0;
        return handler.insert(key, (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long extractFluid(FluidResource key, long amount, TransactionContext tx) {
        var handler = fluidHandler();
        if (handler == null || amount <= 0 || !canRead() || !passes(key)) return 0;
        return handler.extract(key, (int) Math.min(amount, Integer.MAX_VALUE), tx);
    }

    public long insertFluidNow(FluidResource key, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = insertFluid(key, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }

    public long extractFluidNow(FluidResource key, long amount) {
        if (busy || Transaction.getCurrentOpenedTransaction() != null) return 0;
        busy = true;
        try (Transaction tx = Transaction.openRoot()) {
            long moved = extractFluid(key, amount, tx);
            if (moved > 0) tx.commit();
            return moved;
        } finally {
            busy = false;
        }
    }
}
