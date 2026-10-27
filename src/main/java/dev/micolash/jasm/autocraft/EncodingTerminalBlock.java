package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jspecify.annotations.Nullable;

/** The Encoding Terminal. Only its owner and the players they trust can open it. */
public class EncodingTerminalBlock extends MachineBlock {
    @Override
    protected MapCodec<EncodingTerminalBlock> codec() {
        return simpleCodec(EncodingTerminalBlock::new);
    }

    public EncodingTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** A player who sets up a network with this encoder gets a Deck linked to it, as if they had paired it here. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (by instanceof ServerPlayer player && !(by instanceof FakePlayer)
                && level.getBlockEntity(pos) instanceof EncodingTerminalBlockEntity terminal) {
            terminal.linkPlacerDeck(player);
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EncodingTerminalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, JasmBlocks.ENCODING_TERMINAL_ENTITY.get(), EncodingTerminalBlockEntity::serverTick);
    }
}
