package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.tether.NetworkTetherBlockEntity;
import dev.micolash.jasm.tether.TetherStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws what moves in a Network Tether: the Network Brain's power pole in its glass tube, and its operator at the Brain's
 * desk. On watch the operator types through the Brain's desk loop, the screen glows and the pole spins, lit; without
 * power the pole stands dark, the screen is off and the operator dozes face down on the desk; with nobody inside the
 * stool is empty and a "brb" note hangs on the tube's glass from a string over a nail. The rest is the block's own model.
 */
public class NetworkTetherRenderer implements BlockEntityRenderer<NetworkTetherBlockEntity, NetworkTetherRenderer.State> {
    /** The extra models, by where they are, so they can be registered. */
    private static final Map<StandaloneModelKey<BlockStateModelPart>, Identifier> MODELS = new LinkedHashMap<>();
    private static final StandaloneModelKey<BlockStateModelPart> GLASS = key("block/network_tether_glass");
    private static final StandaloneModelKey<BlockStateModelPart> POLE_OFF = key("block/network_tether_pole_off");
    private static final StandaloneModelKey<BlockStateModelPart> ZZZ = key("block/network_tether_zzz");
    private static final StandaloneModelKey<BlockStateModelPart> BRB = key("block/network_tether_brb");
    private static final StandaloneModelKey<BlockStateModelPart> EYES_CLOSED = key("block/network_tether_eyes_closed");

    /** The pole is built round the block's middle; the tube stands this far from it, in model pixels. */
    private static final float POLE_X = -2.4F;
    private static final float POLE_Z = 2.8F;
    /** How far the pole stands turned while it is still. */
    private static final float POLE_REST = 20;
    /** Where the desk scene's middle is: on the stool, in model pixels, and how big it is drawn. */
    private static final float SCENE_X = 10.4F;
    private static final float SCENE_Y = 3.875F;
    private static final float SCENE_Z = 4.4F;
    private static final float SCENE_SCALE = 0.55F;
    /** A dozing operator sits this far back, in model pixels, so its head stays inside the block. */
    private static final float DOZE_BACK = 2;

    /** How the operator lies on the desk: per part its move, turn and size, as the Bitling's loops have them. */
    private static final Map<String, float[]> DOZING = Map.of(
            "root", new float[]{0, -0.3F, 0, 0, 0, 0, 1, 1, 1},
            "body", new float[]{0, 0, 0, -24, 0, 0, 1, 1, 1},
            "head", new float[]{0, 0, 0, -26, 0, 9, 1, 1, 1},
            "antenna", new float[]{0, 0, 0, -22, 0, 18, 1, 1, 1},
            "arm_left", new float[]{0, 0, 0, 64, 0, 30, 1, 1, 1},
            "arm_right", new float[]{0, 0, 0, 58, 0, -32, 1, 1, 1});

    private static StandaloneModelKey<BlockStateModelPart> key(String path) {
        Identifier id = Jasm.id(path);
        StandaloneModelKey<BlockStateModelPart> key = new StandaloneModelKey<>(id::toString);
        MODELS.put(key, id);
        return key;
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        MODELS.forEach((key, id) -> event.register(key, SimpleUnbakedStandaloneModel.simpleModelWrapper(id)));
    }

    private final BrainScenes scenes;

    public NetworkTetherRenderer(BlockEntityRendererProvider.Context context) {
        scenes = new BrainScenes(Minecraft.getInstance().getResourceManager());
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(NetworkTetherBlockEntity tether, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(tether, state, partialTicks, cameraPosition, breakProgress);
        state.facing = tether.getBlockState().hasProperty(MachineBlock.FACING) ? tether.getBlockState().getValue(MachineBlock.FACING) : Direction.NORTH;
        state.mood = tether.shownMood();
        state.kind = tether.shownKind();
        Level level = tether.getLevel();
        // The Brain's clock: the world's time, so every desk types in step.
        state.seconds = level == null ? 0 : (level.getGameTime() + partialTicks) / 20.0;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ModelManager models = Minecraft.getInstance().getModelManager();
        boolean watch = state.mood == TetherStatus.Mood.WATCH;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        submitPole(state, watch, poseStack, collector, models);
        submitScene(state, poseStack, collector, models);
        if (state.mood == TetherStatus.Mood.DOZE) {
            draw(models.getStandaloneModel(ZZZ), false, state.lightCoords, poseStack, collector);
        } else if (state.mood == TetherStatus.Mood.EMPTY) {
            draw(models.getStandaloneModel(BRB), false, state.lightCoords, poseStack, collector);
        }
        draw(models.getStandaloneModel(GLASS), true, state.lightCoords, poseStack, collector);
        poseStack.popPose();
    }

    /** The Brain's pole, moved into the tube: spinning and glowing on watch, otherwise standing still and dark. */
    private static void submitPole(State state, boolean watch, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        poseStack.pushPose();
        poseStack.translate(POLE_X / 16, 0, POLE_Z / 16);
        float turn = watch ? (float) (state.seconds * BrainScenes.PILLAR_DEGREES_PER_SECOND % 360) : POLE_REST;
        poseStack.translate(0.5F, 0, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(turn));
        poseStack.translate(-0.5F, 0, -0.5F);
        BlockStateModelPart pole = models.getStandaloneModel(watch ? NetworkBrainRenderer.PILLAR : POLE_OFF);
        draw(pole, false, watch ? BrainScenes.GLOW : state.lightCoords, poseStack, collector);
        poseStack.popPose();
    }

    /** The Brain's desk on the stool, and the operator at it. */
    private void submitScene(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        BrainScenes.Scene desk = BrainScenes.DESK;
        boolean watch = state.mood == TetherStatus.Mood.WATCH;
        float time = (float) (state.seconds % scenes.loopLength(desk));
        poseStack.pushPose();
        poseStack.translate(SCENE_X / 16, SCENE_Y / 16, SCENE_Z / 16);

        poseStack.pushPose();
        BrainScenes.enter(desk, SCENE_SCALE, 0, poseStack);
        // The screen only shows while someone works at it.
        scenes.submitProps(desk, watch ? time : 0, watch, state.lightCoords, poseStack, collector, models);
        if (watch) {
            scenes.submitBitling(state.kind, scenes.pose(desk, time), Set.of(), state.lightCoords, poseStack, collector, models);
        }
        poseStack.popPose();

        if (state.mood == TetherStatus.Mood.DOZE) {
            poseStack.pushPose();
            poseStack.translate(0, 0, DOZE_BACK / 16);
            BrainScenes.enter(desk, SCENE_SCALE, 0, poseStack);
            scenes.submitBitling(state.kind, DOZING, Set.of("eyes"), state.lightCoords, poseStack, collector, models);
            BrainScenes.submitPart(models.getStandaloneModel(EYES_CLOSED), "eyes", scenes.animations(), DOZING, false, state.lightCoords,
                    poseStack, collector);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void draw(@Nullable BlockStateModelPart model, boolean seeThrough, int light, PoseStack poseStack, SubmitNodeCollector collector) {
        if (model != null) {
            collector.submitBlockModel(poseStack, seeThrough ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(), List.of(model),
                    BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        TetherStatus.Mood mood = TetherStatus.Mood.EMPTY;
        BitlingKind kind = BitlingKind.BASIC;
        double seconds;
    }
}
