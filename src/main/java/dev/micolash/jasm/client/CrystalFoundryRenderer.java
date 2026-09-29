package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws the Data Crystal Cluster growing in the Foundry's chamber: it starts small on the pad and swells to full size as
 * each crystal finishes, then starts over for the next one.
 */
public class CrystalFoundryRenderer implements BlockEntityRenderer<CrystalFoundryBlockEntity, CrystalFoundryRenderer.State> {
    private static final Identifier CLUSTER_ID = Jasm.id("block/data_crystal_cluster");
    private static final StandaloneModelKey<BlockStateModelPart> CLUSTER = new StandaloneModelKey<>(CLUSTER_ID::toString);

    /** The middle of the pad, in model pixels, and the size of the cluster at the start and at the end. */
    private static final float PAD_X = 8;
    private static final float PAD_Y = 4;
    private static final float PAD_Z = 7.3F;
    private static final float SMALLEST = 0.1F;
    private static final float LARGEST = 0.7F;
    /** Bright enough to glow inside the closed chamber. */
    private static final int LIGHT = 0xF000F0;

    public CrystalFoundryRenderer(BlockEntityRendererProvider.Context context) {}

    static void registerModels(ModelEvent.RegisterStandalone event) {
        event.register(CLUSTER, SimpleUnbakedStandaloneModel.simpleModelWrapper(CLUSTER_ID));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CrystalFoundryBlockEntity foundry, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(foundry, state, partialTicks, cameraPosition, breakProgress);
        state.facing = foundry.getBlockState().getValue(MachineBlock.FACING);
        Level level = foundry.getLevel();
        state.growth = level == null ? -1 : foundry.shownGrowth(level.getGameTime(), partialTicks);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.growth < 0) {
            return;
        }
        BlockStateModelPart model = Minecraft.getInstance().getModelManager().getStandaloneModel(CLUSTER);
        if (model == null) {
            return;
        }
        float size = SMALLEST + (LARGEST - SMALLEST) * state.growth;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, 180 - state.facing.toYRot());
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        poseStack.translate(PAD_X / 16, PAD_Y / 16, PAD_Z / 16);
        poseStack.scale(size, size, size);
        poseStack.translate(-8F / 16, 0, -8F / 16);
        collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                LIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        float growth = -1;
    }
}
