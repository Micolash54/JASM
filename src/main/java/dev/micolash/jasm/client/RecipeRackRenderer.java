package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.network.MachineBlock;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws a card in every filled slot of a Recipe Rack. The card model sits in the top-left slot; slot 0 to 3 run
 * left to right along the top row, 4 to 7 the row below, and so on, matching the rack's screen.
 */
public class RecipeRackRenderer implements BlockEntityRenderer<RecipeRackBlockEntity, RecipeRackRenderer.State> {
    public static final StandaloneModelKey<BlockStateModelPart> CARD_MODEL = new StandaloneModelKey<>(() -> "jasm:recipe_rack_card");
    public static final Identifier CARD_MODEL_ID = Jasm.id("block/recipe_rack_card");
    /** Distance between two slots, in blocks. */
    private static final float SLOT_STEP = 3 / 16F;

    public RecipeRackRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
        RecipeRackBlockEntity rack, State state, float partialTicks, Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress
    ) {
        BlockEntityRenderer.super.extractRenderState(rack, state, partialTicks, cameraPosition, breakProgress);
        state.facing = rack.getBlockState().getValue(MachineBlock.FACING);
        state.cards = 0;
        for (int slot = 0; slot < RecipeRackBlockEntity.SLOTS; slot++) {
            if (rack.showsCard(slot)) {
                state.cards |= 1 << slot;
            }
        }
        // The rack is solid, so its own spot is dark: light the cards from the block in front of it.
        if (rack.getLevel() != null) {
            state.lightCoords = LightCoordsUtil.getLightCoords(rack.getLevel(), rack.getBlockPos().relative(state.facing));
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.cards == 0) {
            return;
        }
        BlockStateModelPart card = Minecraft.getInstance().getModelManager().getStandaloneModel(CARD_MODEL);
        if (card == null) {
            return;
        }
        List<BlockStateModelPart> parts = List.of(card);
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, 180 - state.facing.toYRot());
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        for (int slot = 0; slot < RecipeRackBlockEntity.SLOTS; slot++) {
            if ((state.cards & (1 << slot)) == 0) {
                continue;
            }
            poseStack.pushPose();
            // Seen from the front, the model's left is its +x side.
            poseStack.translate(-(slot % 4) * SLOT_STEP, -(slot / 4) * SLOT_STEP, 0);
            collector.submitBlockModel(
                poseStack, Sheets.cutoutBlockItemSheet(), parts, BlockModelRenderState.EMPTY_TINTS, state.lightCoords, OverlayTexture.NO_OVERLAY, 0
            );
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        int cards;
    }
}
