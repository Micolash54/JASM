package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.station.StationBitling;
import dev.micolash.jasm.station.StationBitling.Act;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
import org.jspecify.annotations.Nullable;

/**
 * Draws the Bitling that roams around its station: the same chibi robot, parts and loops as in the Chip Workshop, about
 * two thirds of a block tall. Each part is its own model, turned around its joint every frame. A change of loop eases out
 * of wherever the Bitling really is, and when it looks around it turns its head.
 */
public class StationBitlingRenderer extends EntityRenderer<StationBitling, StationBitlingRenderer.State> {
    /** How big the model is drawn, and where its feet are in it, in model pixels. */
    private static final float SCALE = 0.57F;
    private static final float FEET_X = 8;
    private static final float FEET_Z = 7.75F;
    /** Seconds a change from one loop to the next takes. */
    private static final float BLEND_SECONDS = 0.35F;
    /** How quickly the head follows where it wants to look; higher is quicker. */
    private static final float HEAD_SPEED = 5;

    private final BitlingAnimations animations;
    private final Map<StationBitling, Motion> motions = new WeakHashMap<>();

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
        state.kind = bitling.kind();
        state.bodyYaw = Mth.rotLerp(partialTicks, bitling.yBodyRotO, bitling.yBodyRot);
        state.hurt = bitling.hurtTime > 0;
        Motion motion = motions.computeIfAbsent(bitling, b -> new Motion(b.getId()));
        state.pose = motion.update(bitling.act(), (bitling.tickCount + partialTicks) / 20F);
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

    /** The name of the loop to play for an act: the first of {@link #clipsFor} that exists. */
    private String clipName(Act act) {
        String[] names = clipsFor(act);
        for (String name : names) {
            if (animations.clip(name) != null) {
                return name;
            }
        }
        return names[names.length - 1];
    }

    /** Where one Bitling's loops and head are, kept from frame to frame. */
    private final class Motion {
        private final int seed;
        private String clip = "";
        private float clipStart;
        private Act act = Act.STAND;
        private float actStart;
        /** The pose it is easing out of, and when that began. */
        private @Nullable Map<String, float[]> from;
        private float fromStart;
        private Map<String, float[]> shown = Map.of();
        private float headYaw;
        private float headTilt;
        private float lastTime = -1;

        Motion(int seed) {
            this.seed = seed;
        }

        Map<String, float[]> update(Act now, float time) {
            if (now != act) {
                act = now;
                actStart = time;
            }
            String name = clipName(now);
            // A new loop starts from the beginning, easing out of the pose on screen. Two acts that share a loop, like
            // standing and looking around, just carry on with it.
            if (!name.equals(clip)) {
                if (!clip.isEmpty()) {
                    from = shown;
                    fromStart = time;
                }
                clip = name;
                clipStart = time;
            }
            BitlingAnimations.Clip playing = animations.clip(clip);
            float length = playing == null ? 0 : playing.length();
            Map<String, float[]> pose = animations.sample(playing, length <= 0 ? 0 : (time - clipStart) % length);
            if (from != null) {
                float t = Mth.clamp((time - fromStart) / BLEND_SECONDS, 0, 1);
                pose = BitlingAnimations.mix(from, pose, t * t * (3 - 2 * t));
                if (t >= 1) {
                    from = null;
                }
            }
            shown = pose;

            // The head drifts towards where it wants to look, so it never snaps.
            float step = lastTime < 0 ? 1 : 1 - (float) Math.exp(-Mth.clamp(time - lastTime, 0, 0.25F) * HEAD_SPEED);
            lastTime = time;
            float[] look = lookAt(time - actStart);
            headYaw += (look[0] - headYaw) * step;
            headTilt += (look[1] - headTilt) * step;
            float[] head = pose.get("head");
            if (head == null || Math.abs(headYaw) + Math.abs(headTilt) < 0.01F) {
                return pose;
            }
            Map<String, float[]> turned = new LinkedHashMap<>(pose);
            float[] h = head.clone();
            h[4] += headYaw;
            h[5] += headTilt;
            turned.put("head", h);
            return turned;
        }

        /** Where the head wants to be, turn and tilt in degrees, {@code seconds} into the current act. */
        private float[] lookAt(float seconds) {
            if (act != Act.LOOK) {
                return new float[] {0, 0};
            }
            // Glance one way, then the other, then a curious tilt, then back to the front.
            float side = (seed & 1) == 0 ? 1 : -1;
            if (seconds < 1.1F) {
                return new float[] {side * 40, 0};
            }
            if (seconds < 2.3F) {
                return new float[] {-side * 40, 0};
            }
            if (seconds < 3.3F) {
                return new float[] {side * 12, -side * 14};
            }
            return new float[] {0, 0};
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(state.hurt));

        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.bodyYaw));
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
            animations.pose(part, state.pose, poseStack);
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, overlay, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    public static class State extends EntityRenderState {
        BitlingKind kind = BitlingKind.BASIC;
        Map<String, float[]> pose = Map.of();
        float bodyYaw;
        boolean hurt;
    }
}
