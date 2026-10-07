package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.bay.BayBlockEntity;
import dev.micolash.jasm.bay.BayKind;
import dev.micolash.jasm.bay.BayStatus;
import dev.micolash.jasm.bay.BayTiming;
import dev.micolash.jasm.core.BitlingKind;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a bay's Bitlings at work, the boxes on its shelf and the work lamp. Each action plays one work clip over the
 * cycle, ending on the hit. With two or more Speed Upgrades two Bitlings share the work: the one whose turn it is does
 * the second half of the clip while the other gets ready with the first half.
 */
public class BayRenderer implements BlockEntityRenderer<BayBlockEntity, BayRenderer.State> {
    private static final String[] BODY = {"leg_left", "leg_right", "body", "arm_left", "arm_right", "head", "eyes", "antenna"};
    private static final StandaloneModelKey<BlockStateModelPart> HAT = key("block/bitling/hat");
    private static final StandaloneModelKey<BlockStateModelPart> GOGGLES = key("block/bitling/goggles");
    private static final StandaloneModelKey<BlockStateModelPart> EMITTER = key("block/bay/emitter");
    private static final StandaloneModelKey<BlockStateModelPart> TRIPOD = key("block/bay/tripod");
    private static final StandaloneModelKey<BlockStateModelPart> BOX = key("block/bay/box");
    private static final StandaloneModelKey<BlockStateModelPart> LAMP = key("block/bay/lamp_on");
    private static final Identifier BEAM = Jasm.id("textures/block/bay/beam.png");
    private static final Identifier BEAM_SCOOP = Jasm.id("textures/block/bay/beam_scoop.png");

    /**
     * Shelf spots for the boxes in model pixels, laid out like the grid seen from the front: top row first, each row
     * from the player's left. A box stands on the shelf for each grid slot that holds something.
     */
    private static final float[][] BOXES = {
            {10.375F, 10.25F, 11.75F}, {6.5F, 10.25F, 11.75F}, {2.625F, 10.25F, 11.75F},
            {10.375F, 6.25F, 11.75F}, {6.5F, 6.25F, 11.75F}, {2.625F, 6.25F, 11.75F},
            {10.375F, 2, 11.75F}, {6.5F, 2, 11.75F}, {2.625F, 2, 11.75F}};
    /** Where the Bitlings stand (feet centre, model pixels): one in the middle, or two side by side. */
    private static final float SPOT_Y = 2;
    private static final float DEPLOY_Z = 6.5F;
    private static final float DEMOLISH_Z = 10;
    private static final float[] ONE = {8};
    private static final float[] TWO = {11, 5};
    private static final float SCALE = 0.5F;
    private static final float FEET_X = 8;
    private static final float FEET_Z = 7.75F;
    /** The second Bitling is this far behind in the resting loops, so the two don't move in step. */
    private static final float SECOND_OFFSET = 1.3F;
    /** Ticks to ease from resting into work and back. */
    private static final float BLEND_TICKS = 4;
    /** The held item shows once it has been grabbed, and the beam once the action starts. */
    private static final float HELD_FROM = 0.48F;
    private static final float BEAM_FROM = 0.5F;
    /** How big the held item is, as a block drawn on the ground (a quarter block) scaled up to 5/16 of the Bitling. */
    private static final float HELD_SCALE = 1.25F;
    private static final float BEAM_HALF_WIDTH = 0.3F / 16;
    /** While the filter refuses the block in front, the Bitling shakes its head once and again every this many seconds. */
    private static final float REFUSE_EVERY = 5;
    /** Seconds to ease into the head shake and back out. */
    private static final float REFUSE_BLEND = 0.15F;

    private final BitlingAnimations animations;
    private final ItemModelResolver itemModelResolver;

    public BayRenderer(BlockEntityRendererProvider.Context context) {
        animations = BitlingAnimations.load(Minecraft.getInstance().getResourceManager(), Jasm.id("animations/bay_bitling.json"));
        itemModelResolver = context.itemModelResolver();
    }

    private static StandaloneModelKey<BlockStateModelPart> key(String path) {
        String id = Jasm.id(path).toString();
        return new StandaloneModelKey<>(() -> id);
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        register(event, HAT, "block/bitling/hat");
        register(event, GOGGLES, "block/bitling/goggles");
        register(event, EMITTER, "block/bay/emitter");
        register(event, TRIPOD, "block/bay/tripod");
        register(event, BOX, "block/bay/box");
        register(event, LAMP, "block/bay/lamp_on");
    }

    private static void register(ModelEvent.RegisterStandalone event, StandaloneModelKey<BlockStateModelPart> key, String path) {
        event.register(key, SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id(path)));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(BayBlockEntity bay, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(bay, state, partialTicks, cameraPosition, breakProgress);
        state.kind = bay.kind();
        state.facing = bay.facing();
        state.status = bay.shownStatus();
        state.boxes = bay.shownBoxes();
        state.pair = bay.shownPair();
        state.scoop = bay.clientScoop();
        state.turn = bay.clientTurn();
        state.rested = bay.clientRested();
        Level level = bay.getLevel();
        ItemStack held = bay.shownHeld();
        if (state.kind == BayKind.DEPLOYMENT && !held.isEmpty()) {
            itemModelResolver.updateForTopItem(state.held, held, ItemDisplayContext.GROUND, level, null, (int) bay.getBlockPos().asLong());
        } else {
            state.held.clear();
        }
        state.cycle = -1;
        state.since = Float.MAX_VALUE;
        state.after = Float.MAX_VALUE;
        if (level == null) return;
        long start = bay.clientCycleStart();
        int ticks = bay.clientCycleTicks();
        if (start != Long.MIN_VALUE && ticks > 0) {
            float since = level.getGameTime() - start + partialTicks;
            if (since < ticks) {
                state.cycle = Math.max(0, since / ticks);
                state.ticks = ticks;
                state.since = since;
            } else {
                state.after = since - ticks;
                state.ticks = ticks;
            }
        }
        // Each bay is a little out of step with the next, so a row of them doesn't move as one.
        long offset = Math.floorMod(bay.getBlockPos().asLong() * 7919L, 2000L);
        state.seconds = (level.getGameTime() + offset + partialTicks) / 20F;
        long refused = bay.clientRefusedSince();
        // Not seen arriving (the bay was already refusing when it came into view): start with the next shake, not now.
        state.refused = refused == Long.MIN_VALUE ? state.seconds + REFUSE_EVERY / 2
                : (level.getGameTime() - refused + partialTicks) / 20F;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        turnToFacing(poseStack, state.facing);
        if (state.status != BayStatus.NO_POWER) {
            draw(models.getStandaloneModel(LAMP), RenderTypes.cutoutMovingBlock(), poseStack, collector, LightCoordsUtil.FULL_BRIGHT);
        }
        BlockStateModelPart box = models.getStandaloneModel(BOX);
        for (int i = 0; i < BOXES.length; i++) {
            if ((state.boxes & 1 << i) == 0) continue;
            poseStack.pushPose();
            poseStack.translate(BOXES[i][0] / 16, BOXES[i][1] / 16, BOXES[i][2] / 16);
            draw(box, Sheets.cutoutBlockItemSheet(), poseStack, collector, state.lightCoords);
            poseStack.popPose();
        }
        float[] spots = state.pair ? TWO : ONE;
        for (int i = 0; i < spots.length; i++) {
            bitling(state, i, spots[i], models, poseStack, collector);
        }
        poseStack.popPose();
    }

    /** Turns the model, made facing north, the way the bay faces, as its blockstate does. */
    private static void turnToFacing(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (facing == Direction.UP) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90));
        } else if (facing == Direction.DOWN) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);
    }

    private void bitling(State state, int index, float spotX, ModelManager models, PoseStack poseStack, SubmitNodeCollector collector) {
        boolean demolition = state.kind == BayKind.DEMOLITION;
        String workName = demolition ? "demolish_work" : state.pair && index == 1 ? "deploy_work_b" : "deploy_work";
        BitlingAnimations.Clip work = animations.clip(workName);
        float workLength = work == null ? 1 : work.length();
        BitlingAnimations.Clip rest = animations.clip(switch (state.status) {
            case NO_POWER -> "bay_doze";
            case PAUSED -> "bay_calm";
            default -> "bay_idle";
        });
        float restTime = rest == null || rest.length() <= 0 ? 0 : (state.seconds + index * SECOND_OFFSET) % rest.length();
        boolean myTurn = !state.pair || state.turn == (index == 0);

        // How far through the work clip this Bitling is, 0 to 1, or -1 while resting.
        float t = -1;
        Map<String, float[]> pose;
        if (state.cycle >= 0) {
            t = BayTiming.clip(BayTiming.progress(state.cycle, state.pair, myTurn), BayTiming.window(state.ticks, state.pair));
            pose = animations.sample(work, t * workLength);
            if (state.rested && state.since < BLEND_TICKS) {
                // Straight into the middle of the clip after a rest: ease in from where it was.
                pose = BitlingAnimations.mix(animations.sample(rest, restTime), pose, smooth(state.since / BLEND_TICKS));
            }
        } else {
            pose = animations.sample(rest, restTime);
            if (state.status == BayStatus.FILTERED) pose = refuse(state, index, pose);
            if (state.after < BLEND_TICKS) {
                float end = BayTiming.clip(BayTiming.progress(1, state.pair, myTurn), BayTiming.window(state.ticks, state.pair));
                pose = BitlingAnimations.mix(animations.sample(work, end * workLength), pose, smooth(state.after / BLEND_TICKS));
            }
        }

        poseStack.pushPose();
        poseStack.translate(spotX / 16, SPOT_Y / 16, (demolition ? DEMOLISH_Z : DEPLOY_Z) / 16);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-FEET_X / 16, 0, -FEET_Z / 16);
        Map<String, StandaloneModelKey<BlockStateModelPart>> body = ChipWorkshopRenderer.KEYS.get(BitlingKind.BASIC);
        for (String part : BODY) {
            part(models.getStandaloneModel(body.get(part)), part, pose, poseStack, collector, state.lightCoords);
        }
        if (demolition) {
            part(models.getStandaloneModel(GOGGLES), "goggles", pose, poseStack, collector, state.lightCoords);
            part(models.getStandaloneModel(EMITTER), "emitter", pose, poseStack, collector, state.lightCoords);
            part(models.getStandaloneModel(TRIPOD), "tripod", pose, poseStack, collector, state.lightCoords);
        } else {
            part(models.getStandaloneModel(HAT), "hat", pose, poseStack, collector, state.lightCoords);
            if (t >= HELD_FROM && !state.held.isEmpty()) held(state, pose, poseStack, collector);
        }
        poseStack.popPose();

        if (demolition && t >= BEAM_FROM) beam(state, spotX, pose, poseStack, collector);
    }

    private Map<String, float[]> refuse(State state, int index, Map<String, float[]> resting) {
        BitlingAnimations.Clip clip = animations.clip("bay_refuse");
        if (clip == null || clip.length() <= 0) return resting;
        // The second Bitling a moment later, so the two don't shake as one.
        float time = state.refused - index * 0.2F;
        if (time < 0) return resting;
        time %= REFUSE_EVERY;
        if (time >= clip.length()) return resting;
        float blend = smooth(Math.min(time, clip.length() - time) / REFUSE_BLEND);
        return BitlingAnimations.mix(resting, animations.sample(clip, time), blend);
    }

    private void part(@Nullable BlockStateModelPart model, String bone, Map<String, float[]> pose, PoseStack poseStack,
            SubmitNodeCollector collector, int light) {
        if (model == null) return;
        poseStack.pushPose();
        animations.pose(bone, pose, poseStack);
        draw(model, Sheets.cutoutBlockItemSheet(), poseStack, collector, light);
        poseStack.popPose();
    }

    /** The block about to be placed, carried between both hands. */
    private void held(State state, Map<String, float[]> pose, PoseStack poseStack, SubmitNodeCollector collector) {
        BitlingAnimations.Bone bone = animations.bones().get("held");
        if (bone == null) return;
        float[] pivot = bone.pivot();
        poseStack.pushPose();
        animations.pose("held", pose, poseStack);
        poseStack.translate(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
        poseStack.scale(HELD_SCALE, HELD_SCALE, HELD_SCALE);
        // On the ground an item sits a little above its middle; bring it back to the hands.
        poseStack.translate(0, -0.15F, 0);
        state.held.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /** From the laser's lens straight out to the bay's front face: two crossed strips that glow. */
    private void beam(State state, float spotX, Map<String, float[]> pose, PoseStack poseStack, SubmitNodeCollector collector) {
        BitlingAnimations.Bone bone = animations.bones().get("emitter_tip");
        if (bone == null) return;
        // Where the lens is in the block, found with the same moves as the Bitling but on a pose of its own.
        PoseStack lens = new PoseStack();
        lens.translate(spotX / 16, SPOT_Y / 16, DEMOLISH_Z / 16);
        lens.scale(SCALE, SCALE, SCALE);
        lens.translate(-FEET_X / 16, 0, -FEET_Z / 16);
        animations.pose("emitter_tip", pose, lens);
        float[] pivot = bone.pivot();
        Vector3f tip = lens.last().pose().transformPosition(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16, new Vector3f());
        if (tip.z <= 0) return;
        RenderType type = RenderTypes.beaconBeam(state.scoop ? BEAM_SCOOP : BEAM, true);
        collector.submitCustomGeometry(poseStack, type, (p, buffer) -> {
            float w = BEAM_HALF_WIDTH;
            strip(p, buffer, tip.x - w, tip.y, tip.x + w, tip.y, tip.z);
            strip(p, buffer, tip.x, tip.y - w, tip.x, tip.y + w, tip.z);
        });
    }

    /** One strip from {@code z} to the front face, seen from both sides. u runs across it, v along it. */
    private static void strip(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float x1, float y1, float z) {
        vertex(pose, buffer, x0, y0, z, 0, 0);
        vertex(pose, buffer, x1, y1, z, 1, 0);
        vertex(pose, buffer, x1, y1, 0, 1, 1);
        vertex(pose, buffer, x0, y0, 0, 0, 1);
        vertex(pose, buffer, x0, y0, 0, 0, 1);
        vertex(pose, buffer, x1, y1, 0, 1, 1);
        vertex(pose, buffer, x1, y1, z, 1, 0);
        vertex(pose, buffer, x0, y0, z, 0, 0);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float u, float v) {
        buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static float smooth(float t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void draw(@Nullable BlockStateModelPart part, RenderType renderType, PoseStack poseStack, SubmitNodeCollector collector,
            int light) {
        if (part != null) {
            collector.submitBlockModel(poseStack, renderType, List.of(part), BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    public static class State extends BlockEntityRenderState {
        BayKind kind = BayKind.DEPLOYMENT;
        Direction facing = Direction.NORTH;
        BayStatus status = BayStatus.SLEEPING;
        /** One bit per grid slot that holds something. */
        int boxes;
        boolean pair;
        boolean scoop;
        boolean turn;
        boolean rested;
        final ItemStackRenderState held = new ItemStackRenderState();
        /** How far through the running cycle, 0 to 1, or -1 between cycles. */
        float cycle = -1;
        /** The running or last cycle's length. */
        int ticks = 20;
        /** Ticks since the running cycle started, and since the last one ended. */
        float since;
        float after;
        float seconds;
        float refused;
    }
}
