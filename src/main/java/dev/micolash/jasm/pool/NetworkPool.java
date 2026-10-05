package dev.micolash.jasm.pool;

import dev.micolash.jasm.config.JasmConfig;
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

    /** Lists the blocks again if the listing is old. One listing serves items and fluids and everyone who asks. */
    private void refresh() {
        long now = level.getGameTime();
        if (snapshotTick != Long.MIN_VALUE && now >= snapshotTick && now - snapshotTick < JasmConfig.POOL_SNAPSHOT_TICKS.getAsInt()) return;
        Map<ItemResource, Long> freshItems = new HashMap<>();
        Map<FluidResource, Long> freshFluids = new HashMap<>();
        for (PoolStore store : stores(null)) {
            store.scan(freshItems);
            store.scanFluids(freshFluids);
        }
        snapshotTick = now;
        if (!freshItems.equals(items) || !freshFluids.equals(fluids)) {
            items = freshItems;
            fluids = freshFluids;
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
}
