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
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
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

    private static final java.util.Map<dev.micolash.jasm.transfer.TransferPortKind, StandaloneModelKey<BlockStateModelPart>> TRANSFER_MODELS = new java.util.EnumMap<>(dev.micolash.jasm.transfer.TransferPortKind.class);
    static {
        for (var kind : dev.micolash.jasm.transfer.TransferPortKind.values()) TRANSFER_MODELS.put(kind, new StandaloneModelKey<>(() -> "jasm:" + kind.id()));
    }
    static void registerTransferModels(net.neoforged.neoforge.client.event.ModelEvent.RegisterStandalone event) {
        TRANSFER_MODELS.forEach((kind, key) -> event.register(key, net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id("block/" + kind.id()))));
    }

    public DataCableRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(DataCableBlockEntity cable, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(cable, state, partialTicks, cameraPosition, breakProgress);
        state.ports = 0;
        java.util.Arrays.fill(state.kinds, null);
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
            var progresses = minecraft.levelRenderer.destructionProgress.get(cable.getBlockPos().asLong());
            if (progresses != null && !progresses.isEmpty()
                    && minecraft.level.getEntity(progresses.last().getId()) instanceof net.minecraft.world.entity.player.Player player) {
                state.breakingPort = DataCableBlock.selectedPort(minecraft.level, cable.getBlockPos(), player);
            }
        }
        for (Direction side : Direction.values()) if (cable.port(side) != null) {
            state.ports |= 1 << side.ordinal();
            if (cable.port(side) instanceof dev.micolash.jasm.transfer.TransferPortBlockEntity port) state.kinds[side.ordinal()] = port.kind();
        }
    }

    @Override
    public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.cableParts.isEmpty()) {
            collector.submitBlockModel(poses, Sheets.cutoutBlockItemSheet(), state.cableParts, BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
        if (state.breakProgress != null && state.breakingPort == null) {
            for (var part : state.cableParts) {
                collector.submitBreakingBlockModel(poses, new SingleVariant(part), 0, state.breakProgress.progress());
            }
        }

        for (Direction side : Direction.values()) {
            if ((state.ports & (1 << side.ordinal())) == 0) continue;
            var kind = state.kinds[side.ordinal()];
            BlockStateModelPart model = Minecraft.getInstance().getModelManager().getStandaloneModel(kind == null ? PORT : TRANSFER_MODELS.get(kind));
            if (model == null) continue;
            poses.pushPose();
            poses.translate(0.5F, 0.5F, 0.5F);
            switch (side) {
                case NORTH -> {}
                case SOUTH -> poses.mulPose(Axis.YP.rotationDegrees(180));
                case EAST -> poses.mulPose(Axis.YP.rotationDegrees(-90));
                case WEST -> poses.mulPose(Axis.YP.rotationDegrees(90));
                case UP -> poses.mulPose(Axis.XP.rotationDegrees(90));
                case DOWN -> poses.mulPose(Axis.XP.rotationDegrees(-90));
            }
            poses.translate(-0.5F, -0.5F, -0.5F);
            collector.submitBlockModel(poses, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            if (state.breakProgress != null && state.breakingPort == side) {
                collector.submitBreakingBlockModel(poses, new SingleVariant(model), 0, state.breakProgress.progress());
            }
            poses.popPose();
        }
    }

    public static class State extends BlockEntityRenderState {
        int ports;
        final dev.micolash.jasm.transfer.TransferPortKind[] kinds = new dev.micolash.jasm.transfer.TransferPortKind[6];
        List<BlockStateModelPart> cableParts = List.of();
        @Nullable Direction breakingPort;
    }
}
