package dev.micolash.jasm.battery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.PowerReceiver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Touching Battery blocks: one battery. It holds no power of its own, only its blocks do, so it can be thrown away and
 * worked out again at any time. That happens whenever a battery block beside it is placed, broken, loaded or unloaded.
 * Each tick it tops up the machines it touches, and those on the networks of the cables it touches.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BatteryGroup {
    /** Largest battery walked; blocks beyond it make a battery of their own. */
    public static final int MAX_BLOCKS = 4_096;
    /** Ticks between redraws of the charge. */
    private static final int SHOW_TICKS = 10;
    /** Ticks the power flow is averaged over. */
    private static final int FLOW_TICKS = 20;

    private static final Map<ServerLevel, Map<BlockPos, BatteryGroup>> LEVELS = new WeakHashMap<>();

    private final List<BatteryBlockEntity> blocks;
    private final int bottom;
    private final int top;
    private final EnergyHandler energy = new Energy();
    private final List<PowerReceiver> receivers = new ArrayList<>();
    private final Set<Object> fed = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean alive = true;
    private boolean sidesDirty = true;
    private long tickedAt = Long.MIN_VALUE;
    private long shown = -1;
    /** What the blocks held at the start of this tick, or -1 before the first. */
    private long stored = -1;
    private long goneIn;
    private long goneOut;
    private long flowIn;
    private long flowOut;

    private BatteryGroup(List<BatteryBlockEntity> blocks) {
        this.blocks = blocks;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (BatteryBlockEntity block : blocks) {
            low = Math.min(low, block.getBlockPos().getY());
            high = Math.max(high, block.getBlockPos().getY());
        }
        this.bottom = low;
        this.top = high;
    }

    /** The battery {@code start} belongs to, walked from it when not known yet. Never pulls in a chunk. */
    static BatteryGroup of(ServerLevel level, BatteryBlockEntity start) {
        Map<BlockPos, BatteryGroup> known = LEVELS.computeIfAbsent(level, l -> new HashMap<>());
        BatteryGroup found = known.get(start.getBlockPos());
        if (found != null && found.alive) {
            return found;
        }
        List<BatteryBlockEntity> blocks = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BatteryBlockEntity> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.getBlockPos());
        while (!queue.isEmpty() && blocks.size() < MAX_BLOCKS) {
            BatteryBlockEntity block = queue.poll();
            blocks.add(block);
            for (Direction side : Direction.values()) {
                BlockPos next = block.getBlockPos().relative(side);
                if (!seen.add(next) || !level.isLoaded(next)) continue;
                BatteryGroup other = known.get(next);
                if (other != null && other.alive) continue;
                if (level.getBlockEntity(next) instanceof BatteryBlockEntity battery && !battery.isRemoved()) {
                    queue.add(battery);
                }
            }
        }
        BatteryGroup group = new BatteryGroup(List.copyOf(blocks));
        for (BatteryBlockEntity block : blocks) {
            known.put(block.getBlockPos(), group);
        }
        return group;
    }

    /** A battery block came or went at {@code pos}: the batteries there and beside it are worked out again. */
    static void forgetAround(ServerLevel level, BlockPos pos) {
        Map<BlockPos, BatteryGroup> known = LEVELS.get(level);
        if (known == null) return;
        forget(known, known.get(pos));
        for (Direction side : Direction.values()) {
            forget(known, known.get(pos.relative(side)));
        }
        if (known.isEmpty()) {
            LEVELS.remove(level);
        }
    }

    private static void forget(Map<BlockPos, BatteryGroup> known, BatteryGroup group) {
        if (group == null || !group.alive) return;
        group.alive = false;
        for (BatteryBlockEntity block : group.blocks) {
            known.remove(block.getBlockPos(), group);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level);
        }
    }

    /** The batteries of these blocks, each once. Removed blocks are left out. */
    public static List<EnergyHandler> distinct(ServerLevel level, Collection<BatteryBlockEntity> blocks) {
        List<EnergyHandler> found = new ArrayList<>();
        Set<BatteryGroup> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BatteryBlockEntity block : blocks) {
            if (!block.isRemoved()) {
                BatteryGroup group = block.group(level);
                if (seen.add(group)) found.add(group.energy);
            }
        }
        return found;
    }

    boolean alive() {
        return alive;
    }

    /** The block that runs the battery's tick. */
    BatteryBlockEntity leader() {
        return blocks.getFirst();
    }

    public int size() {
        return blocks.size();
    }

    /** The whole battery's power. */
    public EnergyHandler energy() {
        return energy;
    }

    /** Something beside one of its blocks changed. */
    void sidesChanged() {
        sidesDirty = true;
    }

    /** What the whole battery holds, as of this tick. */
    public long stored() {
        if (stored < 0) {
            stored = energy.getAmountAsLong();
        }
        return stored;
    }

    /** FE a tick that went in and came out, averaged over the last second. */
    public long flowIn() {
        return flowIn;
    }

    public long flowOut() {
        return flowOut;
    }

    /** A block's share changed by {@code delta}: counted towards the flow. */
    void changed(long delta) {
        if (delta > 0) goneIn += delta;
        else goneOut -= delta;
    }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now == tickedAt) return;
        tickedAt = now;
        stored = energy.getAmountAsLong();
        long phase = now + leader().getBlockPos().asLong();
        if (phase % FLOW_TICKS == 0) {
            flowIn = Math.round(goneIn / (double) FLOW_TICKS);
            flowOut = Math.round(goneOut / (double) FLOW_TICKS);
            goneIn = 0;
            goneOut = 0;
        }
        supply(level, now);
        if (phase % SHOW_TICKS == 0) {
            show(level);
        }
    }

    /** Tops up what it touches: machines and Archives directly, and through a cable the machines of its network. */
    private void supply(ServerLevel level, long now) {
        // Blocks placed without a neighbour update (commands, structures) are caught within a second.
        if (sidesDirty || (now + leader().getBlockPos().asLong()) % 20 == 0) {
            findReceivers(level);
        }
        if (receivers.isEmpty()) return;
        long held = stored;
        fed.clear();
        for (PowerReceiver receiver : receivers) {
            if (held <= 0) return;
            if (!receiver.live()) {
                sidesDirty = true;
                continue;
            }
            Object target = receiver.target();
            if (target == null || !fed.add(target)) continue;
            try (Transaction tx = Transaction.openRoot()) {
                int given = receiver.insertFromBattery((int) Math.min(Integer.MAX_VALUE, held), tx);
                if (given > 0 && energy.extract(given, tx) == given) {
                    tx.commit();
                    held -= given;
                }
            }
        }
    }

    private void findReceivers(ServerLevel level) {
        sidesDirty = false;
        receivers.clear();
        for (BatteryBlockEntity block : blocks) {
            for (Direction side : Direction.values()) {
                BlockPos next = block.getBlockPos().relative(side);
                if (!level.isLoaded(next)) continue;
                BlockEntity entity = level.getBlockEntity(next);
                if (entity == null || entity instanceof BatteryBlockEntity) continue;
                PowerReceiver receiver = PowerReceiver.of(entity);
                if (receiver != null) receivers.add(receiver);
            }
        }
    }

    /** Lights each block's core so the whole battery fills from its lowest block up, by how full it is, and the top lines when full. */
    private void show(ServerLevel level) {
        long held = energy.getAmountAsLong();
        if (held == shown) return;
        shown = held;
        long capacity = energy.getCapacityAsLong();
        int height = (top - bottom + 1) * 16;
        // The lowest and highest two pixels sit behind the base and the cap, so an empty battery shows none and a full one all.
        int lit = capacity <= 0 || held <= 0 ? 0 : 2 + (int) Math.max(1, Math.round((double) held / capacity * (height - 4)));
        boolean full = capacity > 0 && held >= capacity;
        for (BatteryBlockEntity block : blocks) {
            int charge = Math.clamp(lit - (block.getBlockPos().getY() - bottom) * 16, 0, 16);
            BlockState state = block.getBlockState();
            if (state.getBlock() instanceof BatteryBlock
                    && (state.getValue(BatteryBlock.CHARGE) != charge || state.getValue(BatteryBlock.FULL) != full)) {
                level.setBlock(block.getBlockPos(), state.setValue(BatteryBlock.CHARGE, charge).setValue(BatteryBlock.FULL, full),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    /** Spread over the blocks: an equal part each first, then the rest to whoever can still take or give it. */
    private final class Energy implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            long total = 0;
            for (BatteryBlockEntity block : blocks) total += block.energy().getAmountAsInt();
            return total;
        }

        @Override
        public long getCapacityAsLong() {
            return (long) blocks.size() * BatteryBlockEntity.CAPACITY;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0 || !alive) return 0;
            int wanting = 0;
            for (BatteryBlockEntity block : blocks) {
                if (block.energy().getAmountAsInt() < BatteryBlockEntity.CAPACITY) wanting++;
            }
            if (wanting == 0) return 0;
            int share = Math.max(1, amount / wanting);
            int taken = 0;
            for (int pass = 0; pass < 2 && taken < amount; pass++) {
                for (BatteryBlockEntity block : blocks) {
                    if (taken >= amount) break;
                    SimpleEnergyHandler part = block.energy();
                    taken += part.insert(pass == 0 ? Math.min(share, amount - taken) : amount - taken, transaction);
                }
            }
            return taken;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            if (amount <= 0 || !alive) return 0;
            int holding = 0;
            for (BatteryBlockEntity block : blocks) {
                if (block.energy().getAmountAsInt() > 0) holding++;
            }
            if (holding == 0) return 0;
            int share = Math.max(1, amount / holding);
            int given = 0;
            for (int pass = 0; pass < 2 && given < amount; pass++) {
                for (BatteryBlockEntity block : blocks) {
                    if (given >= amount) break;
                    SimpleEnergyHandler part = block.energy();
                    given += part.extract(pass == 0 ? Math.min(share, amount - given) : amount - given, transaction);
                }
            }
            return given;
        }
    }
}
