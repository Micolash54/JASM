package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.bitling.BitlingBody;
import dev.micolash.jasm.bitling.BitlingBody.Act;
import dev.micolash.jasm.core.BitlingKind;
import java.util.ArrayList;
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
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * Draws a Bitling out in the world, at a station or wild: the same chibi robot, parts and loops as in the Chip Workshop, about
 * two thirds of a block tall. Each part is its own model, turned around its joint every frame. A change of loop eases out
 * of wherever the Bitling really is, and when it looks around it turns its head.
 */
public class BitlingBodyRenderer extends EntityRenderer<BitlingBody, BitlingBodyRenderer.State> {
    /** How big the model is drawn, and where its feet are in it, in model pixels. */
    private static final float SCALE = 0.57F;
    private static final float FEET_X = 8;
    private static final float FEET_Z = 7.75F;
    /** Seconds a change from one loop to the next takes. */
    private static final float BLEND_SECONDS = 0.35F;
    /**
     * Walking and sprinting are measured in Minecraft's own walk measure (about four to a block): how much of it one step
     * cycle covers at a walk and at a sprint, so the legs keep pace with the ground, longer strides the faster it goes.
     */
    private static final float WALK_CYCLE = 1.5F;
    private static final float SPRINT_CYCLE = 3.25F;
    /** Walk measure a tick at which the swing is full size, the sprint starts to show, and the sprint is fully in. */
    private static final float WALK_PACE = 0.15F;
    private static final float SPRINT_FROM = 0.25F;
    private static final float SPRINT_PACE = 0.6F;
    /** The stand-in name for walking and sprinting, which follow the ground rather than the clock. */
    private static final String STRIDE = "stride";
    /** How quickly the idle loop's own head sweep makes way while it looks around; higher is quicker. */
    private static final float LOOK_FADE = 6;
    /** The furthest the head ever turns from the body, real look and loop together. */
    private static final float HEAD_LIMIT = 50;
    /** The one-off moves played on the way into and out of a nap, before the next loop takes over. */
    private static final String LIE_DOWN = "lie_down";
    private static final String GET_UP = "get_up";
    /**
     * The hop plays when a walking Bitling jumps up a step: from a quick squash as it is seen leaving the ground, the pose
     * it falls in held until it is seen to land, then the landing until it walks on. Seconds into the hop loop of each, and
     * how far up counts as off the ground, in blocks.
     */
    private static final String HOP = "hop";
    private static final float HOP_TAKE_OFF = 0.08F;
    private static final float HOP_FALLING = 0.42F;
    private static final float HOP_WALKS_ON = 0.6F;
    private static final float HOP_OFF_GROUND = 0.02F;
    /** The walk gives way to the hop quickly, as the jump is short. */
    private static final float HOP_BLEND_SECONDS = 0.1F;
    /** Where a held item sits from the right arm's joint, in model pixels, and how big it is drawn. */
    private static final float HAND_X = 0;
    private static final float HAND_Y = -5;
    private static final float HAND_Z = -2;
    private static final float HELD_SCALE = 0.35F;

    private final BitlingAnimations animations;
    private final ItemModelResolver itemModelResolver;
    private final Map<BitlingBody, Motion> motions = new WeakHashMap<>();
    /** Each part split into the faces that take the light around them and the faces that light themselves. */
    private final Map<BlockStateModelPart, @Nullable BlockStateModelPart[]> split = new WeakHashMap<>();

    public BitlingBodyRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.28F;
        this.animations = BitlingAnimations.load(Minecraft.getInstance().getResourceManager());
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(BitlingBody bitling, State state, float partialTicks) {
        super.extractRenderState(bitling, state, partialTicks);
        state.kind = bitling.kind();
        state.bodyYaw = Mth.rotLerp(partialTicks, bitling.yBodyRotO, bitling.yBodyRot);
        state.hurt = bitling.hurtTime > 0;
        // A Bitling outside the world, like one in a guide picture, never ticks, so its loops can't play: it stands still.
        if (bitling.level() != Minecraft.getInstance().level) {
            state.pose = animations.sample(animations.clip(clipName(Act.STAND)), 0);
            state.held.clear();
            return;
        }
        Motion motion = motions.computeIfAbsent(bitling, b -> new Motion());
        float headYaw = Mth.wrapDegrees(Mth.rotLerp(partialTicks, bitling.yHeadRotO, bitling.yHeadRot) - state.bodyYaw);
        state.pose = motion.update(bitling.act(), (bitling.tickCount + partialTicks) / 20F, bitling.walkAnimation.position(partialTicks),
                bitling.walkAnimation.speed(partialTicks), headYaw, bitling.getXRot(partialTicks), bitling.glancing(), (float) state.y,
                bitling.onGround());
        // It only ever holds something up to look at it or eat it, whichever loop stands in for that.
        Act act = bitling.act();
        if (act == Act.INSPECT || act == Act.EAT) {
            itemModelResolver.updateForLiving(state.held, bitling.held(), ItemDisplayContext.GROUND, bitling);
        } else {
            state.held.clear();
        }
    }

    /** The loops to try for each thing the Bitling can be doing, best first. */
    private static String[] clipsFor(Act act) {
        return switch (act) {
            case STAND, LOOK -> new String[]{"idle"};
            case WALK -> new String[]{"walk", "working"};
            case SPRINT -> new String[]{"sprint", "walk", "working"};
            case HOP -> new String[]{"idle"};
            case REST -> new String[]{"napping"};
            case TIRED -> new String[]{"tired_walk", "walk", "working"};
            case STARTLED -> new String[]{"startled", "idle"};
            case PETTED -> new String[]{"petted", "idle"};
            case RECHARGE -> new String[]{"recharging", "napping"};
            case INSPECT -> new String[]{"inspect", "idle"};
            case NOD -> new String[]{"nod", "idle"};
            case EAT -> new String[]{"eat", "idle"};
            case SHAKE -> new String[]{"head_shake", "startled", "idle"};
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

    private static boolean lying(@Nullable Act act) {
        return act == Act.REST || act == Act.RECHARGE;
    }

    private static boolean striding(Act act) {
        return act == Act.WALK || act == Act.SPRINT || act == Act.TIRED;
    }

    /** Where one Bitling's loops and stride are, kept from frame to frame. */
    private final class Motion {
        private String clip = "";
        private float clipStart;
        /** The pose it is easing out of, and when that began; a stride it stops still plays out as it slows. */
        private @Nullable Map<String, float[]> from;
        private boolean fromStride;
        private float fromStart;
        private Map<String, float[]> shown = Map.of();
        /** How far through its step cycle it is, counted in cycles, and the walk measure last time. */
        private float phase;
        private float lastWalk = Float.NaN;
        /** How much the idle loop's head sweep has made way for a real look, from 0 to 1. */
        private float looking;
        private float lastTime = -1;
        /** The act last frame, and a one-off move (lying down, getting up) playing before the act's own loop, if any. */
        private @Nullable Act act;
        private String move = "";
        private float moveStart;
        private float blendSeconds = BLEND_SECONDS;
        /**
         * A jump up a step: its height when last on the ground, when it was seen to leave the ground and to land (-1 till
         * then), and its height last frame.
         */
        private float groundY;
        private float tookOff = -1;
        private float landed = -1;
        private float lastY;

        Map<String, float[]> update(Act now, float time, float walk, float speed, float headYaw, float headPitch, boolean glancing,
                float y, boolean onGround) {
            // The step cycle moves on with the ground covered, in longer strides the faster it goes.
            float moved = Float.isNaN(lastWalk) ? 0 : walk - lastWalk;
            lastWalk = walk;
            if (moved > 0 && moved < 2) {
                phase += moved / Mth.lerp(Mth.clamp((speed - WALK_PACE) / (SPRINT_PACE - WALK_PACE), 0, 1), WALK_CYCLE, SPRINT_CYCLE);
                phase -= Mth.floor(phase);
            }
            boolean striding = striding(now);
            if (now != act) {
                // Lying down and getting up play in between. A Bitling that bolts off its nap just goes.
                if (act != null && lying(now) && !lying(act)) {
                    startMove(LIE_DOWN, time);
                } else if (act != null && lying(act) && !lying(now) && !striding) {
                    startMove(GET_UP, time);
                } else if (striding || lying(now) != lying(act)) {
                    move = "";
                }
                act = now;
            }
            if (!move.isEmpty() && time - moveStart >= length(move)) {
                move = "";
            }
            // Walking, it jumps up a step: off the ground and going up. A step it walks up, or a drop, is no jump.
            if (tookOff < 0) {
                if (onGround) {
                    groundY = y;
                } else if (striding && y > groundY + HOP_OFF_GROUND) {
                    tookOff = time;
                    landed = -1;
                }
            } else if (landed < 0) {
                if (onGround && y >= lastY) {
                    landed = time;
                }
            } else if (hopTime(time) >= HOP_WALKS_ON) {
                tookOff = -1;
                groundY = y;
            }
            lastY = y;
            String name = !move.isEmpty() ? move : tookOff >= 0 ? HOP : striding ? STRIDE : clipName(now);
            // A new loop starts from the beginning, easing out of the pose on screen. Two acts that share a loop, like
            // standing and looking around, just carry on with it.
            if (!name.equals(clip)) {
                if (!clip.isEmpty()) {
                    fromStride = clip.equals(STRIDE);
                    from = fromStride ? null : shown;
                    fromStart = time;
                }
                clip = name;
                clipStart = time;
                blendSeconds = name.equals(HOP) ? HOP_BLEND_SECONDS : BLEND_SECONDS;
            }
            Map<String, float[]> pose;
            BitlingAnimations.Clip playing = animations.clip(clip);
            float length = playing == null ? 0 : playing.length();
            if (clip.equals(STRIDE)) {
                pose = stride(speed);
            } else if (clip.equals(HOP)) {
                pose = animations.sample(playing, Math.min(hopTime(time), length));
            } else if (clip.equals(move)) {
                pose = animations.sample(playing, Math.min(time - clipStart, length));
            } else {
                pose = animations.sample(playing, length <= 0 ? 0 : (time - clipStart) % length);
            }
            if (from != null || fromStride) {
                float t = Mth.clamp((time - fromStart) / blendSeconds, 0, 1);
                pose = BitlingAnimations.mix(fromStride ? stride(speed) : from, pose, t * t * (3 - 2 * t));
                if (t >= 1) {
                    from = null;
                    fromStride = false;
                }
            }
            shown = pose;

            // The head turns where the Bitling really looks. While it looks around or at a player, the idle loop's own
            // sweep makes way, so the two never add up.
            float step = lastTime < 0 ? 1 : 1 - (float) Math.exp(-Mth.clamp(time - lastTime, 0, 0.25F) * LOOK_FADE);
            lastTime = time;
            looking += ((now == Act.LOOK || glancing ? 1 : 0) - looking) * step;
            float[] head = pose.get("head");
            if (head == null || (looking < 0.001F && Math.abs(headYaw) + Math.abs(headPitch) < 0.01F)) {
                return pose;
            }
            Map<String, float[]> turned = new LinkedHashMap<>(pose);
            float[] h = head.clone();
            h[3] = h[3] * (1 - looking) - headPitch;
            h[4] = Mth.clamp(h[4] * (1 - looking) - headYaw, -HEAD_LIMIT, HEAD_LIMIT);
            turned.put("head", h);
            return turned;
        }

        /** Starts a one-off move, if there is one to play. */
        private void startMove(String name, float time) {
            move = animations.clip(name) == null ? "" : name;
            moveStart = time;
        }

        private float length(String name) {
            BitlingAnimations.Clip playing = animations.clip(name);
            return playing == null ? 0 : playing.length();
        }

        /** How far into the hop loop to be: in the air till it lands, then landing. */
        private float hopTime(float time) {
            if (landed < 0) {
                return Math.min(HOP_TAKE_OFF + time - tookOff, HOP_FALLING);
            }
            return HOP_FALLING + time - landed;
        }

        /**
         * The walk and sprint loops at the current point of the step cycle: the sprint comes in as it speeds up, and the
         * whole swing grows with speed and dies down as it stops.
         */
        private Map<String, float[]> stride(float speed) {
            BitlingAnimations.Clip walk = animations.clip("walk");
            BitlingAnimations.Clip sprint = animations.clip("sprint");
            Map<String, float[]> pose = animations.sample(walk, walk == null ? 0 : phase * walk.length());
            if (sprint != null) {
                float t = Mth.clamp((speed - SPRINT_FROM) / (SPRINT_PACE - SPRINT_FROM), 0, 1);
                pose = BitlingAnimations.mix(pose, animations.sample(sprint, phase * sprint.length()), t * t * (3 - 2 * t));
            }
            return BitlingAnimations.mix(Map.of(), pose, Mth.clamp(speed / WALK_PACE, 0, 1));
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
            @Nullable BlockStateModelPart[] halves = split.computeIfAbsent(model, BitlingBodyRenderer::splitGlow);
            poseStack.pushPose();
            animations.pose(part, state.pose, poseStack);
            if (halves[0] != null) {
                collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(halves[0]), BlockModelRenderState.EMPTY_TINTS,
                        state.lightCoords, overlay, 0);
            }
            // The block shader skips the side shading, so the screen, eyes and antenna light glow evenly from every side.
            if (halves[1] != null) {
                collector.submitBlockModel(poseStack, RenderTypes.cutoutMovingBlock(), List.of(halves[1]), BlockModelRenderState.EMPTY_TINTS,
                        state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            }
            poseStack.popPose();
        }
        BitlingAnimations.Bone arm = animations.bones().get("arm_right");
        if (!state.held.isEmpty() && arm != null) {
            float[] pivot = arm.pivot();
            poseStack.pushPose();
            animations.pose("arm_right", state.pose, poseStack);
            poseStack.translate((pivot[0] + HAND_X) / 16, (pivot[1] + HAND_Y) / 16, (pivot[2] + HAND_Z) / 16);
            poseStack.scale(HELD_SCALE, HELD_SCALE, HELD_SCALE);
            state.held.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /** Splits a part's faces into casing and glow (the faces that light themselves); either half may be missing. */
    private static @Nullable BlockStateModelPart[] splitGlow(BlockStateModelPart model) {
        List<BakedQuad> casing = new ArrayList<>();
        List<BakedQuad> glow = new ArrayList<>();
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : model.getQuads(side)) (quad.materialInfo().lightEmission() > 0 ? glow : casing).add(quad);
        }
        for (BakedQuad quad : model.getQuads(null)) (quad.materialInfo().lightEmission() > 0 ? glow : casing).add(quad);
        if (glow.isEmpty()) {
            return new BlockStateModelPart[]{model, null};
        }
        return new BlockStateModelPart[]{casing.isEmpty() ? null : Quads.of(casing, model), Quads.of(glow, model)};
    }

    private record Quads(List<BakedQuad> quads, boolean smoothLight, Material.Baked particle, int flags)
            implements BlockStateModelPart {
        @SuppressWarnings("deprecation")
        static Quads of(List<BakedQuad> quads, BlockStateModelPart from) {
            return new Quads(List.copyOf(quads), from.useAmbientOcclusion(), from.particleMaterial(), from.materialFlags());
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) {
            return direction == null ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return smoothLight;
        }

        @Override
        public Material.Baked particleMaterial() {
            return particle;
        }

        @Override
        public int materialFlags() {
            return flags;
        }
    }

    public static class State extends EntityRenderState {
        BitlingKind kind = BitlingKind.BASIC;
        Map<String, float[]> pose = Map.of();
        float bodyYaw;
        boolean hurt;
        final ItemStackRenderState held = new ItemStackRenderState();
    }
}
