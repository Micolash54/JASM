package dev.micolash.jasm.crystal;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Every so many powered ticks, gives each touching plant or budding block one extra growth attempt. */
public class ResonatorBlockEntity extends MachineBlockEntity {
    /** Ticks of power it can hold: only a second's worth, so it goes dark soon after its power is cut. */
    private static final int BUFFER_TICKS = 20;
    public static final int CAPACITY = BUFFER_TICKS * 10;
    public static final TagKey<Block> GROWTH_ACCELERATABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("jasm", "growth_acceleratable"));
    private final BlockPos[] neighbours;

    /** Powered ticks since the last pulse. */
    private int sincePulse;

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CRYSTAL_RESONATOR_ENTITY.get(), pos, state, CAPACITY);
        Direction[] sides = Direction.values();
        neighbours = new BlockPos[sides.length];
        for (int i = 0; i < sides.length; i++) neighbours[i] = pos.relative(sides[i]);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ResonatorBlockEntity resonator) {
        resonator.fitBuffer();
        boolean powered = resonator.payForTick();
        BlockState now = resonator.getBlockState();
        if (now.getValue(ResonatorBlock.POWERED) != powered && level.isLoaded(pos)) {
            level.setBlock(pos, now.setValue(ResonatorBlock.POWERED, powered), Block.UPDATE_CLIENTS);
        }
        if (powered && ++resonator.sincePulse >= JasmConfig.RESONATOR_INTERVAL.getAsInt()) {
            resonator.sincePulse = 0;
            resonator.pulse((ServerLevel) level);
        }
    }

    // the drain can be changed in the config, the buffer follows it
    private void fitBuffer() {
        int room = Math.max(1, BUFFER_TICKS * drainPerTick());
        if (energy.getCapacityAsInt() != room) {
            energy.resize(room);
            if (energy.getAmountAsInt() > room) energy.set(room);
        }
    }

    private void pulse(ServerLevel level) {
        for (BlockPos next : neighbours) {
            // Growth also reads the soil or bud faces around the touching block.
            if (!level.hasChunksAt(next.offset(-1, 0, -1), next.offset(1, 0, 1))) continue;
            BlockState there = level.getBlockState(next);
            if (there.getBlock() instanceof SeededAmethystBlock seeded) {
                seeded.grow(there, level, next, level.getRandom(), JasmConfig.RESONATOR_WEAR_CHANCE.get());
            } else if (there.is(GROWTH_ACCELERATABLE)) {
                there.randomTick(level, next, level.getRandom());
            }
        }
    }

    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.RESONATOR_DRAIN.getAsInt();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("since_pulse", sincePulse);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        sincePulse = input.getIntOr("since_pulse", 0);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return NonNullList.create();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {}

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
    }
}
