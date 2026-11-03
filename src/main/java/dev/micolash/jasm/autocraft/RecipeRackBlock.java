package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The Recipe Rack: 16 Filled Recipe Cards for its network. */
public class RecipeRackBlock extends MachineBlock {
    /** Distance between two slots, in pixels. */
    public static final float SLOT_STEP = 3;
    /** How far a card's face stands out of the rack's front, in pixels. What the card makes is drawn just in front of it. */
    public static final float CARD_FACE = 0.5F;

    /**
     * The middle of slot {@code slot}'s card face across, in pixels, on the rack as modelled facing north. Seen from the
     * front, the model's left is its +x side; slot 0 to 3 run left to right along the top row, 4 to 7 the row below.
     */
    public static float slotX(int slot) {
        return 12.5F - SLOT_STEP * (slot % 4);
    }

    /** The middle of slot {@code slot}'s card face up, in pixels. */
    public static float slotY(int slot) {
        return 12.5F - SLOT_STEP * (slot / 4);
    }

    /** How far the rack as modelled facing north is turned round its middle to face {@code facing}, in degrees. */
    public static float yRotation(Direction facing) {
        return 180 - facing.toYRot();
    }

    /** The middle of slot {@code slot}'s card face on a rack facing {@code facing}, in blocks from its lowest corner. */
    public static Vec3 slotFront(Direction facing, int slot) {
        Vec3 modelled = new Vec3(slotX(slot) / 16 - 0.5, slotY(slot) / 16, -CARD_FACE / 16 - 0.5);
        return modelled.yRot((float) Math.toRadians(yRotation(facing))).add(0.5, 0, 0.5);
    }

    @Override
    protected MapCodec<RecipeRackBlock> codec() {
        return simpleCodec(RecipeRackBlock::new);
    }

    public RecipeRackBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RecipeRackBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.RECIPE_RACK_ENTITY.get(), RecipeRackBlockEntity::serverTick);
    }
}
