package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.DataCableBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/** Draws each panel inside its cable's block space. */
public class DataCableRenderer implements BlockEntityRenderer<DataCableBlockEntity, DataCableRenderer.State> {
    public static final StandaloneModelKey<BlockStateModelPart> PORT = new StandaloneModelKey<>(() -> "jasm:thin_access_port");
    public static final Identifier PORT_ID = Jasm.id("block/thin_access_port");

    public DataCableRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(DataCableBlockEntity cable, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(cable, state, partialTicks, cameraPosition, breakProgress);
        state.ports = 0;
        state.cableParts = List.of();
        state.breakingPort = null;
        var minecraft = Minecraft.getInstance();
        if (cable.getBlockState().getValue(DataCableBlock.HAS_PORTS)) {
            var model = minecraft.getModelManager().getBlockStateModelSet().get(cable.getBlockState());
            var parts = new ArrayList<BlockStateModelPart>();
            model.collectParts(minecraft.level, cable.getBlockPos(), cable.getBlockState(),
                    RandomSource.create(cable.getBlockState().getSeed(cable.getBlockPos())), parts);
            state.cableParts = List.copyOf(parts);
        }
        if (breakProgress != null && minecraft.level != null) {
            var progresses = minecraft.level.destructionProgress().get(cable.getBlockPos().asLong());
            if (progresses != null && !progresses.isEmpty()
                    && minecraft.level.getEntity(progresses.last().getId()) instanceof net.minecraft.world.entity.player.Player player) {
                state.breakingPort = DataCableBlock.selectedPort(minecraft.level, cable.getBlockPos(), player);
            }
        }
        for (Direction side : Direction.values()) if (cable.port(side) != null) state.ports |= 1 << side.ordinal();
    }

    @Override
    public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.cableParts.isEmpty()) return;
        collector.submitBlockModel(poses, Sheets.cutoutBlockItemSheet(), state.cableParts, BlockModelRenderState.EMPTY_TINTS,
                state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        if (state.breakProgress != null && state.breakingPort == null) {
            collector.submitBreakingBlockModel(poses, state.cableParts, state.breakProgress.progress(), false);
        }
        BlockStateModelPart model = Minecraft.getInstance().getModelManager().getStandaloneModel(PORT);
        if (model == null) return;
        for (Direction side : Direction.values()) {
            if ((state.ports & (1 << side.ordinal())) == 0) continue;
            poses.pushPose();
            poses.translate(0.5F, 0.5F, 0.5F);
            switch (side) {
                case NORTH -> {}
                case SOUTH -> poses.rotateDegrees(Axis.YP, 180);
                case EAST -> poses.rotateDegrees(Axis.YP, -90);
                case WEST -> poses.rotateDegrees(Axis.YP, 90);
                case UP -> poses.rotateDegrees(Axis.XP, 90);
                case DOWN -> poses.rotateDegrees(Axis.XP, -90);
            }
            poses.translate(-0.5F, -0.5F, -0.5F);
            collector.submitBlockModel(poses, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            if (state.breakProgress != null && state.breakingPort == side) {
                collector.submitBreakingBlockModel(poses, List.of(model), state.breakProgress.progress(), false);
            }
            poses.popPose();
        }
    }

    public static class State extends BlockEntityRenderState {
        int ports;
        List<BlockStateModelPart> cableParts = List.of();
        @Nullable Direction breakingPort;
    }
}
