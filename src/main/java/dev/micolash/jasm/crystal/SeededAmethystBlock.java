package dev.micolash.jasm.crystal;

import dev.micolash.jasm.bitling.CrystalAttraction;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * A Block of Amethyst with a Crystal Seed in it. Grows Data Crystal buds like budding amethyst, and each growth may wear it one
 * stage closer to a plain Block of Amethyst.
 */
public class SeededAmethystBlock extends AmethystBlock {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** The block this one wears into. */
    private final Supplier<? extends Block> next;

    public SeededAmethystBlock(Properties properties, Supplier<? extends Block> next) {
        super(properties);
        this.next = next;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(BuddingAmethystBlock.GROWTH_CHANCE) == 0) {
            grow(state, level, pos, random);
        }
        CrystalAttraction.tryAttract(level, pos, random);
    }

    /** One growth attempt on a random face. */
    public void grow(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        grow(state, level, pos, random, JasmConfig.SEEDED_WEAR_CHANCE.get());
    }

    public void grow(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, double wearChance) {
        grow(level, pos, DIRECTIONS[random.nextInt(DIRECTIONS.length)], random, wearChance);
    }

    /** One growth attempt on {@code face}. Returns true if a bud grew. */
    public boolean grow(ServerLevel level, BlockPos pos, Direction face, RandomSource random, double wearChance) {
        BlockPos growPos = pos.relative(face);
        BlockState there = level.getBlockState(growPos);
        Block nextStage = nextBud(there, face);
        if (nextStage == null) {
            return false;
        }
        level.setBlockAndUpdate(growPos, nextStage.defaultBlockState()
                .setValue(AmethystClusterBlock.FACING, face)
                .setValue(AmethystClusterBlock.WATERLOGGED, there.getFluidState().is(Fluids.WATER)));
        if (random.nextDouble() < wearChance) {
            level.setBlockAndUpdate(pos, next.get().defaultBlockState());
        }
        return true;
    }

    private static @Nullable Block nextBud(BlockState there, Direction face) {
        if (BuddingAmethystBlock.canClusterGrowAtState(there)) {
            return JasmBlocks.SMALL_DATA_CRYSTAL_BUD.get();
        }
        if (!(there.getBlock() instanceof AmethystClusterBlock) || there.getValue(AmethystClusterBlock.FACING) != face) {
            return null;
        }
        if (there.is(JasmBlocks.SMALL_DATA_CRYSTAL_BUD.get())) {
            return JasmBlocks.MEDIUM_DATA_CRYSTAL_BUD.get();
        }
        if (there.is(JasmBlocks.MEDIUM_DATA_CRYSTAL_BUD.get())) {
            return JasmBlocks.LARGE_DATA_CRYSTAL_BUD.get();
        }
        if (there.is(JasmBlocks.LARGE_DATA_CRYSTAL_BUD.get())) {
            return JasmBlocks.DATA_CRYSTAL_CLUSTER.get();
        }
        return null;
    }
}
