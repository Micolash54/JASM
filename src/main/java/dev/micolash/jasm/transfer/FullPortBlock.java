package dev.micolash.jasm.transfer;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.autocraft.AccessPortBlock;
import dev.micolash.jasm.pool.StoragePortBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/** A full-block Input, Output, Input Output or Storage Port: the Access Port's shape, serving every block it touches. */
public class FullPortBlock extends AccessPortBlock implements LiquidBlockContainer {
    public static final MapCodec<FullPortBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            TransferPortKind.CODEC.fieldOf("kind").forGetter(FullPortBlock::kind),
            propertiesCodec()).apply(i, FullPortBlock::new));

    private final TransferPortKind kind;

    public FullPortBlock(TransferPortKind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public TransferPortKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<FullPortBlock> codec() {
        return CODEC;
    }

    // running water would wash the port away, along with the settings it holds
    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean canPlaceLiquid(@Nullable LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid type) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return kind == TransferPortKind.STORAGE ? StoragePortBlockEntity.full(pos, state) : TransferPortBlockEntity.full(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (tickLevel, pos, tickState, entity) -> {
            if (entity instanceof TransferPortBlockEntity port) port.tickFull();
        };
    }
}
