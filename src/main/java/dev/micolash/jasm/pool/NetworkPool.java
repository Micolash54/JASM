package dev.micolash.jasm.pool;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.core.PoolRouter;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.Networks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The Storage Ports of one cable network. It lives as long as the network object does: when the network changes it is
 * thrown away and found again, so nothing stays behind.
 */
public final class NetworkPool {
    /** Keyed by the network object, which the network finder replaces on every change. */
    private static final Map<CableNetwork, NetworkPool> POOLS = new WeakHashMap<>();

    private final ServerLevel level;
    private final CableNetwork network;
    private Map<ItemResource, Long> items = Map.of();
    private Map<FluidResource, Long> fluids = Map.of();
    private Map<MaterialKey, Long> materials = Map.of();
    private long snapshotTick = Long.MIN_VALUE;
    private long version;

    private NetworkPool(ServerLevel level, CableNetwork network) {
        this.level = level;
        this.network = network;
    }

    /** The pool of {@code network}, or null when it has no Storage Port. */
    public static @Nullable NetworkPool of(ServerLevel level, CableNetwork network) {
        if (network.machines(StoragePortBlockEntity.class).isEmpty()) return null;
        return POOLS.computeIfAbsent(network, n -> new NetworkPool(level, n));
    }

    /** A port's settings changed: its network lists the blocks again next time. */
    public static void touch(ServerLevel level, BlockPos pos) {
        CableNetwork network = Networks.at(level, pos);
        NetworkPool pool = network == null ? null : POOLS.get(network);
        if (pool != null) pool.relist();
    }

    public void relist() {
        snapshotTick = Long.MIN_VALUE;
    }

    /** The blocks the pool may use right now, one per block (the highest priority port wins), leaving out {@code avoid}. */
    public List<PoolStore> stores(@Nullable BlockPos avoid) {
        Map<BlockPos, PoolStore> byBlock = new LinkedHashMap<>();
        for (StoragePortBlockEntity port : network.machines(StoragePortBlockEntity.class)) {
            if (!port.storeActive() || port.chestPos().equals(avoid)) continue;
            PoolStore store = port.store();
            PoolStore known = byBlock.get(store.chestPos());
            if (known == null || store.priority() > known.priority()) byBlock.put(store.chestPos(), store);
        }
        return new ArrayList<>(byBlock.values());
    }

    /** Lists the blocks again if the listing is old. One listing serves items, fluids and materials and everyone who asks. */
    private void refresh() {
        long now = level.getGameTime();
        if (snapshotTick != Long.MIN_VALUE && now >= snapshotTick && now - snapshotTick < Tuning.POOL_SNAPSHOT_TICKS) return;
        Map<ItemResource, Long> freshItems = new HashMap<>();
        Map<FluidResource, Long> freshFluids = new HashMap<>();
        Map<MaterialKey, Long> freshMaterials = new HashMap<>();
        for (PoolStore store : stores(null)) {
            store.scan(freshItems);
            store.scanFluids(freshFluids);
            store.scanMaterials(freshMaterials);
        }
        snapshotTick = now;
        if (!freshItems.equals(items) || !freshFluids.equals(fluids) || !freshMaterials.equals(materials)) {
            items = freshItems;
            fluids = freshFluids;
            materials = freshMaterials;
            version++;
        }
    }

    /** The items the blocks hold. */
    public Map<ItemResource, Long> contents() {
        refresh();
        return items;
    }

    /** The fluids the blocks hold, in millibuckets. */
    public Map<FluidResource, Long> fluidContents() {
        refresh();
        return fluids;
    }

    /** Other mods' materials in the blocks, by ID. */
    public Map<MaterialKey, Long> materialContents() {
        refresh();
        return materials;
    }

    /** Goes up each time the listing really changed. */
    public long version() { return version; }

    /** The live count in the blocks, not the listing. */
    public long count(ItemResource key) {
        long total = 0;
        for (PoolStore store : stores(null)) total += store.count(key);
        return total;
    }

    public long countFluid(FluidResource key) {
        long total = 0;
        for (PoolStore store : stores(null)) total += store.countFluid(key);
        return total;
    }

    /** Takes up to {@code amount} items from the blocks, lowest priority first. Returns how many came out. */
    public long extract(ItemResource key, long amount, TransactionContext tx) {
        long taken = 0;
        for (PoolStore store : PoolRouter.extractOrder(stores(null), key)) {
            if (taken >= amount) break;
            taken += store.extract(key, amount - taken, tx);
        }
        return taken;
    }

    // the exact resources under each name, found again when the listing changes (so a job asking every tick doesn't scan)
    private final Map<MaterialKey, List<Material>> exact = new HashMap<>();
    private long exactVersion = -1;
    private long exactTick = Long.MIN_VALUE;

    /**
     * The exact resources the blocks held under {@code key} at the last look. A look is made when the listing changes,
     * and again, at most once a tick, when a name turns up nothing (the listing can be a moment behind the blocks).
     */
    public List<Material> materialsOf(MaterialKey key) {
        refresh();
        long now = level.getGameTime();
        boolean listingMoved = exactVersion != version;
        if (listingMoved || !exact.containsKey(key) && exactTick != now) {
            exact.clear();
            for (Material material : materialStacks(null).keySet()) exact.computeIfAbsent(material.key(), k -> new ArrayList<>()).add(material);
            exactVersion = version;
            exactTick = now;
        }
        return exact.getOrDefault(key, List.of());
    }

    /**
     * Takes exactly {@code amount} of {@code key} out of the blocks, lowest priority first, or nothing at all (null when
     * they hold less). The pieces are by exact resource, so they can be put back or handed on.
     */
    public @Nullable Map<Material, Long> takeMaterial(MaterialKey key, long amount) {
        Map<Material, Long> taken = new LinkedHashMap<>();
        long left = amount;
        for (Material material : materialsOf(key)) {
            if (left <= 0) break;
            long got = extractMaterialNow(material, left, null);
            if (got > 0) {
                taken.put(material, got);
                left -= got;
            }
        }
        if (left > 0) {
            putBackMaterial(taken);
            // what was looked at is out of date
            exactVersion = -1;
            return null;
        }
        return taken;
    }

    /** Puts pieces from {@link #takeMaterial} back. What no block takes back is logged and lost to the caller. */
    public void putBackMaterial(Map<Material, Long> pieces) {
        pieces.forEach((material, amount) -> {
            long back = insertMaterialNow(material, amount, null);
            if (back < amount) Jasm.LOGGER.warn("{} units of {} did not fit back into the network's storage", amount - back, material.key().id());
        });
    }

    /** What the blocks hold right now, by exact resource and not from the listing. Leaves out {@code avoid}. */
    public Map<Material, Long> materialStacks(@Nullable BlockPos avoid) {
        Map<Material, Long> all = new LinkedHashMap<>();
        for (PoolStore store : stores(avoid)) store.scanMaterialStacks(all);
        return all;
    }

    public long countMaterial(Material material, @Nullable BlockPos avoid) {
        long total = 0;
        for (PoolStore store : stores(avoid)) total += store.countMaterial(material);
        return total;
    }

    /** One store seen as a place for materials, for the router. */
    private record Side(PoolStore store, PoolRouter.Unit<Material> unit) implements PoolRouter.Unit<Material> {
        @Override
        public int priority() { return unit.priority(); }
        @Override
        public boolean wafer() { return false; }
        @Override
        public boolean prefers(Material key) { return unit.prefers(key); }
        @Override
        public boolean canRead() { return unit.canRead(); }
        @Override
        public boolean canWrite() { return unit.canWrite(); }
    }

    private List<Side> sides(@Nullable BlockPos avoid) {
        return stores(avoid).stream().map(store -> new Side(store, store.materialUnit())).toList();
    }

    /** How much of {@code material}, up to {@code most}, the blocks would take. Changes nothing. */
    public long roomMaterial(Material material, long most, @Nullable BlockPos avoid) {
        long room = 0;
        for (Side side : PoolRouter.insertOrder(sides(avoid), material)) {
            if (room >= most) break;
            room += side.store().roomMaterial(material, most - room);
        }
        return room;
    }

    /** Puts up to {@code amount} in, highest priority first. Returns how much went in. */
    public long insertMaterialNow(Material material, long amount, @Nullable BlockPos avoid) {
        long moved = 0;
        for (Side side : PoolRouter.insertOrder(sides(avoid), material)) {
            if (moved >= amount) break;
            moved += side.store().insertMaterialNow(material, amount - moved);
        }
        return moved;
    }

    /** Takes up to {@code amount} out, lowest priority first. Returns how much came out. */
    public long extractMaterialNow(Material material, long amount, @Nullable BlockPos avoid) {
        long taken = 0;
        for (Side side : PoolRouter.extractOrder(sides(avoid), material)) {
            if (taken >= amount) break;
            taken += side.store().extractMaterialNow(material, amount - taken);
        }
        return taken;
    }
}
