package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.station.StationBitling;
import dev.micolash.jasm.station.StationBitling.Act;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.util.Mth;

/**
 * Draws the Bitling that roams around its station: the same chibi robot, parts and loops as in the Chip Workshop, about
 * two thirds of a block tall. Each part is its own model, turned around its joint every frame; a change of loop eases in.
 */
public class StationBitlingRenderer extends EntityRenderer<StationBitling, StationBitlingRenderer.State> {
    /** How big the model is drawn, and where its feet are in it, in model pixels. */
    private static final float SCALE = 0.57F;
    private static final float FEET_X = 8;
    private static final float FEET_Z = 7.75F;
    /** Seconds a change from one loop to the next takes. */
    private static final float BLEND_SECONDS = 0.2F;

    private final BitlingAnimations animations;

    public StationBitlingRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.28F;
        this.animations = BitlingAnimations.load(Minecraft.getInstance().getResourceManager());
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(StationBitling bitling, State state, float partialTicks) {
        super.extractRenderState(bitling, state, partialTicks);
        float age = bitling.tickCount + partialTicks;
        state.kind = bitling.kind();
        state.act = bitling.act();
        state.previousAct = bitling.previousAct();
        state.seconds = age / 20F;
        state.sinceChange = (age - bitling.actSince()) / 20F;
        state.bodyYaw = Mth.rotLerp(partialTicks, bitling.yBodyRotO, bitling.yBodyRot);
        state.hurt = bitling.hurtTime > 0;
    }

    /** The loops to try for each thing the Bitling can be doing, best first. */
    private static String[] clipsFor(Act act) {
        return switch (act) {
            case STAND, LOOK -> new String[] {"idle"};
            case WALK -> new String[] {"walk", "working"};
            case SPRINT -> new String[] {"sprint", "walk", "working"};
            case HOP -> new String[] {"hop", "idle"};
            case REST -> new String[] {"lie_down", "napping"};
            case TIRED -> new String[] {"tired_walk", "walk", "working"};
            case STARTLED -> new String[] {"startled", "idle"};
            case PETTED -> new String[] {"petted", "idle"};
            case RECHARGE -> new String[] {"recharging", "napping"};
        };
    }

    private BitlingAnimations.Clip clip(Act act) {
        for (String name : clipsFor(act)) {
            BitlingAnimations.Clip clip = animations.clip(name);
            if (clip != null) {
                return clip;
            }
        }
        return null;
    }

    private static float loopTime(BitlingAnimations.Clip clip, float seconds) {
        return clip == null || clip.length() <= 0 ? 0 : seconds % clip.length();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        BitlingAnimations.Clip now = clip(state.act);
        BitlingAnimations.Clip before = state.previousAct == state.act ? null : clip(state.previousAct);
        float blend = Mth.clamp(state.sinceChange / BLEND_SECONDS, 0, 1);
        float nowTime = loopTime(now, state.sinceChange);
        float beforeTime = loopTime(before, state.seconds);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(state.hurt));

        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        poseStack.rotateDegrees(Axis.YP, 180 - state.bodyYaw);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-FEET_X / 16, 0, -FEET_Z / 16);
        for (String part : ChipWorkshopRenderer.PARTS) {
            // The chip is only ever in the Workshop's hand.
            if (part.equals("chip")) {
                continue;
            }
            BlockStateModelPart model = models.getStandaloneModel(ChipWorkshopRenderer.KEYS.get(state.kind).get(part));
            if (model == null) {
                continue;
            }
            poseStack.pushPose();
            animations.pose(part, now, nowTime, before, beforeTime, blend, poseStack);
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, overlay, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    public static class State extends EntityRenderState {
        BitlingKind kind = BitlingKind.BASIC;
        Act act = Act.STAND;
        Act previousAct = Act.STAND;
        float seconds;
        float sinceChange;
        float bodyYaw;
        boolean hurt;
    }
}
