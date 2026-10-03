package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.network.MachineBlock;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws the Encoding Terminal's two piles of blank cards, one card per four in the terminal, the left pile filling
 * before the right one, and its screen lit up while a player has it open. The card model is the bottom card of the
 * left pile.
 */
public class EncodingTerminalRenderer implements BlockEntityRenderer<EncodingTerminalBlockEntity, EncodingTerminalRenderer.State> {
    private static final StandaloneModelKey<BlockStateModelPart> CARD_LIGHT = new StandaloneModelKey<>(() -> "jasm:encoding_terminal/card_light");
    private static final StandaloneModelKey<BlockStateModelPart> CARD_DARK = new StandaloneModelKey<>(() -> "jasm:encoding_terminal/card_dark");
    private static final StandaloneModelKey<BlockStateModelPart> SCREEN = new StandaloneModelKey<>(() -> "jasm:encoding_terminal/screen_lit");
    private static final float PIXEL = 1 / 16F;
    /** From the left pile to the right one. */
    private static final float PILE_STEP = 5 * PIXEL;
    private static final float CARD_THICKNESS = 0.5F * PIXEL;
    /** Cards in one pile: the left one fills before the right one starts. */
    private static final int PILE_HEIGHT = 8;

    public EncodingTerminalRenderer(BlockEntityRendererProvider.Context context) {}

    static void registerModels(ModelEvent.RegisterStandalone event) {
        event.register(CARD_LIGHT, SimpleUnbakedStandaloneModel.simpleModelWrapper(model("card_light")));
        event.register(CARD_DARK, SimpleUnbakedStandaloneModel.simpleModelWrapper(model("card_dark")));
        event.register(SCREEN, SimpleUnbakedStandaloneModel.simpleModelWrapper(model("screen_lit")));
    }

    private static Identifier model(String name) {
        return Jasm.id("block/encoding_terminal/" + name);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            EncodingTerminalBlockEntity terminal, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(terminal, state, partialTicks, cameraPosition, breakProgress);
        state.facing = terminal.getBlockState().getValue(MachineBlock.FACING);
        state.cards = terminal.shownCards();
        state.inUse = terminal.shownInUse();
        // The terminal is solid, so its own spot is dark: light the cards from the block in front of it.
        if (terminal.getLevel() != null) {
            state.lightCoords = LevelRenderer.getLightCoords(terminal.getLevel(), terminal.getBlockPos().relative(state.facing));
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.cards == 0 && !state.inUse) {
            return;
        }
        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        for (int i = 0; i < state.cards; i++) {
            int pile = i / PILE_HEIGHT;
            int level = i % PILE_HEIGHT;
            poseStack.pushPose();
            // Seen from the front, the model's left is its +x side.
            poseStack.translate(-pile * PILE_STEP, level * CARD_THICKNESS, 0);
            draw(models.getStandaloneModel(level % 2 == 0 ? CARD_LIGHT : CARD_DARK), poseStack, collector, state.lightCoords);
            poseStack.popPose();
        }
        if (state.inUse) {
            // Block lighting without the item shading, so the screen keeps its full colour.
            draw(models.getStandaloneModel(SCREEN), RenderTypes.cutoutMovingBlock(), poseStack, collector, LightCoordsUtil.FULL_BRIGHT);
        }
        poseStack.popPose();
    }

    private static void draw(@Nullable BlockStateModelPart part, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        draw(part, Sheets.cutoutBlockItemSheet(), poseStack, collector, light);
    }

    private static void draw(@Nullable BlockStateModelPart part, RenderType renderType, PoseStack poseStack, SubmitNodeCollector collector,
            int light) {
        if (part != null) {
            collector.submitBlockModel(poseStack, renderType, List.of(part), BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        int cards;
        boolean inUse;
    }
}
