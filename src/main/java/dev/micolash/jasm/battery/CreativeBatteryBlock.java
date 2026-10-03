package dev.micolash.jasm.battery;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.PowerSourceBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Unlimited power for creative worlds: feeds every touching block that takes FE and charges the item in its slot. */
public class CreativeBatteryBlock extends PowerSourceBlock {
    @Override
    protected MapCodec<CreativeBatteryBlock> codec() {
        return simpleCodec(CreativeBatteryBlock::new);
    }

    public CreativeBatteryBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeBatteryBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, JasmBlocks.CREATIVE_BATTERY_ENTITY.get(), CreativeBatteryBlockEntity::serverTick);
    }
}
