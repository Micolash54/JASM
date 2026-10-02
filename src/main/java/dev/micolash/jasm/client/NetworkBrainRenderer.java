package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BrainCube;
import dev.micolash.jasm.core.BrainSize;
import dev.micolash.jasm.network.MachineBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws the Bitlings busy inside a Network Brain. A single brain plays one little scene after another;
 * between scenes the glass clouds over, the next scene is set out behind it, and the glass clears again. Without power its
 * Bitling naps in the armchair. A 2×2×2 or 3×3×3 cube scales the chamber up to fill it and plays several scenes side by side,
 * each Bitling at its own point in its loop; without power they all curl up where they stand.
 */
public class NetworkBrainRenderer implements BlockEntityRenderer<NetworkBrainBlockEntity, NetworkBrainRenderer.State> {
    /** The brain's frame and floor, drawn scaled up around a big cube. */
    static final Identifier FRAME_ID = Jasm.id("block/network_brain");
    static final StandaloneModelKey<BlockStateModelPart> FRAME = new StandaloneModelKey<>(FRAME_ID::toString);

    /**
     * One scene of a single brain: its loop and props, where its middle is (from the Bitling's feet, in model pixels), how far
     * it is turned so it reads best from the front, and what the Bitling carries. The see-through props, like a hologram, are
     * drawn last.
     */
    private record Scene(String name, float x, float z, float turn, List<String> props, List<String> seeThrough, List<Held> held) {
        String loop() {
            return "brain_" + name;
        }
    }

    /** Something the Bitling carries all through a scene: its model, and the part it hangs from. */
    private record Held(String model, String bone) {}

    private static final List<Scene> SCENES = List.of(
            new Scene("shapes", 0, -1.45F, 0, List.of("base", "box", "block_1", "block_2", "block_3", "ball"), List.of(), List.of()),
            new Scene("desk", 0.5F, -1, 0, List.of("desk", "juice"), List.of("holo"), List.of()),
            new Scene("nap", 0, 0.35F, 0, List.of("bag", "pillow", "zzz"), List.of(), List.of()),
            new Scene("crystal", 2, -0.2F, 0, List.of("stand", "crystal"), List.of(), List.of(new Held("pad", "pad"))),
            new Scene("doodle", 3, -1.7F, -116, List.of("easel"), List.of(), List.of(new Held("toy_marker", "toy"))),
            new Scene("snack", 0, -2.9F, 0, List.of("snack"), List.of(), List.of(new Held("toy_shard", "toy"))));
    /** The scene a brain without power shows. */
    private static final int NAP_SCENE = 2;
    /** How many times each scene plays before the next. */
    private static final int PLAYS_PER_SCENE = 1;
    /** Seconds the glass takes to cloud over or clear, and how long it stays solid while the scene changes. */
    private static final float FADE_SECONDS = 1.6F;
    private static final float SOLID_SECONDS = 0.4F;
    /** The glass, from clear to solid. */
    private static final int GLASS_STAGES = 16;
    /** How big the Bitling and its props are drawn in a single brain. */
    private static final float SCENE_SCALE = 0.5F;
    /** The top of the brain's floor, in model pixels. */
    private static final float FLOOR_Y = 2;

    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> SCENE_MODELS = new HashMap<>();
    private static final List<StandaloneModelKey<BlockStateModelPart>> GLASS = new ArrayList<>();

    static {
        for (Scene scene : SCENES) {
            for (String part : scene.props()) {
                sceneModel(propPath(scene, part));
            }
            for (String part : scene.seeThrough()) {
                sceneModel(propPath(scene, part));
            }
            for (Held held : scene.held()) {
                sceneModel(heldPath(held));
            }
        }
        for (int i = 0; i < GLASS_STAGES; i++) {
            Identifier id = Jasm.id("block/brain_glass/stage_" + i);
            GLASS.add(new StandaloneModelKey<>(id::toString));
        }
    }

    private static String propPath(Scene scene, String part) {
        return "block/brain_prop/" + scene.name() + "/" + part;
    }

    private static String heldPath(Held held) {
        return "block/bitling/" + held.model();
    }

    private static void sceneModel(String path) {
        Identifier id = Jasm.id(path);
        SCENE_MODELS.computeIfAbsent(path, p -> new StandaloneModelKey<>(id::toString));
    }

    /**
     * Where a scene sits in a big cube: its middle, from the cube's corner in model pixels (the front is the low z side), and
     * how far it is turned. Each spot starts its loop a little later than the one before, so no two move in step.
     */
    private record Spot(int scene, float x, float z, float yaw) {}

    private static final int SHAPES = 0;
    private static final int DESK = 1;
    private static final int CRYSTAL = 3;
    private static final int DOODLE = 4;
    private static final int SNACK = 5;
    private static final Spot[] CUBE_2_SPOTS = {new Spot(DESK, 9, 21.5F, 0), new Spot(SHAPES, 16, 10, 0), new Spot(NAP_SCENE, 21, 22, 30)};
    private static final Spot[] CUBE_3_SPOTS = {new Spot(DESK, 22, 14.5F, 0), new Spot(SHAPES, 34.5F, 14, 0),
            new Spot(NAP_SCENE, 35, 35, 35), new Spot(CRYSTAL, 10.5F, 31, 0), new Spot(DOODLE, 24, 33.5F, 0),
            new Spot(SNACK, 11, 14, 0)};
    /** How big the scenes are drawn in a big cube, by size. */
    private static final float CUBE_2_SCALE = 0.65F;
    private static final float CUBE_3_SCALE = 0.8F;
    /** Seconds between one spot's start in its loop and the next one's. */
    private static final float SPOT_STAGGER = 5;
    /** The Bitling model's own feet centre. */
    private static final float FEET_X = 8;

    private final BitlingAnimations animations;
    /** Each scene's props: their parts and how they move. */
    private final Map<String, BitlingAnimations> props = new HashMap<>();
    /** When each brain last fell asleep or woke, so the change eases in. */
    private final Map<NetworkBrainBlockEntity, Wake> wakes = new WeakHashMap<>();

    private record Wake(boolean awake, double since) {}

    public NetworkBrainRenderer(BlockEntityRendererProvider.Context context) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        animations = BitlingAnimations.load(resources);
        for (Scene scene : SCENES) {
            props.put(scene.name(), BitlingAnimations.load(resources, Jasm.id("animations/brain/" + scene.name() + ".json")));
        }
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        event.register(FRAME, SimpleUnbakedStandaloneModel.simpleModelWrapper(FRAME_ID));
        for (Map.Entry<String, StandaloneModelKey<BlockStateModelPart>> e : SCENE_MODELS.entrySet()) {
            event.register(e.getValue(), SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id(e.getKey())));
        }
        for (int i = 0; i < GLASS_STAGES; i++) {
            event.register(GLASS.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id("block/brain_glass/stage_" + i)));
        }
    }

    private static Spot[] spots(BrainSize size) {
        return size == BrainSize.CUBE_2 ? CUBE_2_SPOTS : CUBE_3_SPOTS;
    }

    private static float scale(BrainSize size) {
        return size == BrainSize.CUBE_2 ? CUBE_2_SCALE : CUBE_3_SCALE;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(NetworkBrainBlockEntity brain, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(brain, state, partialTicks, cameraPosition, breakProgress);
        BlockState block = brain.getBlockState();
        state.size = block.hasProperty(NetworkBrainBlock.SIZE) ? block.getValue(NetworkBrainBlock.SIZE) : BrainSize.SINGLE;
        state.awake = block.hasProperty(NetworkBrainBlock.AWAKE) && block.getValue(NetworkBrainBlock.AWAKE);
        state.facing = block.hasProperty(MachineBlock.FACING) ? block.getValue(MachineBlock.FACING) : Direction.NORTH;
        BlockPos at = brain.getBlockPos();
        BrainCube.@Nullable Box box = brain.box();
        // The block and its cube reach the game separately; wait until both agree.
        state.ready = state.size == BrainSize.SINGLE || box != null && box.side() == state.size.side();
        boolean cube = state.size != BrainSize.SINGLE && box != null;
        state.originX = cube ? box.x() - at.getX() : 0;
        state.originY = cube ? box.y() - at.getY() : 0;
        state.originZ = cube ? box.z() - at.getZ() : 0;
        Level level = brain.getLevel();
        if (level != null) {
            state.seconds = (level.getGameTime() + partialTicks) / 20.0;
            int half = state.size.side() / 2;
            // The light in the middle of the cube, so the inside isn't dark.
            state.lightCoords = LevelRenderer.getLightCoords(level, at.offset(state.originX + half, state.originY + half, state.originZ + half));
            Wake wake = wakes.get(brain);
            if (wake == null) {
                wake = new Wake(state.awake, Double.NEGATIVE_INFINITY);
                wakes.put(brain, wake);
            } else if (wake.awake() != state.awake) {
                wake = new Wake(state.awake, state.seconds);
                wakes.put(brain, wake);
            }
            state.wokeOrSlept = wake.since();
            // Every brain starts its scenes at its own point, so two side by side don't move together.
            state.ownSeconds = state.seconds + Math.floorMod(at.asLong() * 0x9E3779B97F4A7C15L, 997L);
            wakeOrSleep(state);
            if (state.size == BrainSize.SINGLE) {
                pickScene(state);
            }
        }
    }

    /** Waking up or falling asleep clouds the glass over, and the Bitlings change what they do behind it. */
    private static void wakeOrSleep(State state) {
        double sinceChange = state.seconds - state.wokeOrSlept;
        state.showAwake = state.awake;
        state.glass = 0;
        if (sinceChange < 2 * FADE_SECONDS + SOLID_SECONDS) {
            if (sinceChange < FADE_SECONDS + SOLID_SECONDS / 2) {
                state.showAwake = !state.awake;
            }
            if (sinceChange < FADE_SECONDS) {
                state.glass = (float) (sinceChange / FADE_SECONDS);
            } else if (sinceChange < FADE_SECONDS + SOLID_SECONDS) {
                state.glass = 1;
            } else {
                state.glass = 1 - (float) ((sinceChange - FADE_SECONDS - SOLID_SECONDS) / FADE_SECONDS);
            }
        }
    }

    /** Which scene a single brain shows now, how far into it, and how clouded its glass is. */
    private void pickScene(State state) {
        double seconds = state.ownSeconds;
        double play = PLAYS_PER_SCENE * loopLength(SCENES.getFirst());
        double period = play + FADE_SECONDS;
        long round = (long) Math.floor(seconds / period);
        double into = seconds - round * period;
        float roundGlass;
        if (into < SOLID_SECONDS) {
            roundGlass = 1;
        } else if (into < SOLID_SECONDS + FADE_SECONDS) {
            roundGlass = 1 - (float) ((into - SOLID_SECONDS) / FADE_SECONDS);
        } else if (into >= play) {
            roundGlass = (float) ((into - play) / FADE_SECONDS);
        } else {
            roundGlass = 0;
        }
        if (state.showAwake) {
            state.scene = (int) Math.floorMod(round, (long) SCENES.size());
            state.sceneTime = into;
            state.glass = Math.max(roundGlass, state.glass);
        } else {
            state.scene = NAP_SCENE;
            state.sceneTime = state.seconds;
        }
    }

    private float loopLength(Scene scene) {
        BitlingAnimations.Clip clip = animations.clip(scene.loop());
        return clip == null || clip.length() <= 0 ? 30 : clip.length();
    }

    /** Drawn even when the brain's own block is off screen, as long as part of its cube is in view. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(NetworkBrainBlockEntity brain) {
        BrainCube.@Nullable Box box = brain.box();
        if (box == null) {
            return new AABB(brain.getBlockPos());
        }
        return new AABB(box.x(), box.y(), box.z(), box.x() + box.side(), box.y() + box.side(), box.z() + box.side());
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.ready) {
            return;
        }
        ModelManager models = Minecraft.getInstance().getModelManager();
        float side = state.size.side();
        poseStack.pushPose();
        poseStack.translate(state.originX, state.originY, state.originZ);
        // Turned the way the brain faces, like its block model.
        poseStack.translate(side / 2, 0, side / 2);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        poseStack.translate(-side / 2, 0, -side / 2);
        if (state.size == BrainSize.SINGLE) {
            submitSingle(state, poseStack, collector, models);
        } else {
            submitCube(state, poseStack, collector, models);
        }
        poseStack.popPose();
    }

    /** A single brain's scene, then the glass around it. */
    private void submitSingle(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        Scene scene = SCENES.get(state.scene);
        poseStack.pushPose();
        poseStack.translate(0.5, FLOOR_Y / 16, 0.5);
        submitScene(scene, (float) (Math.max(0, state.sceneTime) % loopLength(scene)), true, SCENE_SCALE, state, poseStack, collector,
                models);
        poseStack.popPose();
        submitGlass(state, poseStack, collector, models);
    }

    /** A big cube: the frame scaled up to fill it, its scenes at their spots, then the glass. */
    private void submitCube(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        float side = state.size.side();
        BlockStateModelPart frame = models.getStandaloneModel(FRAME);
        if (frame != null) {
            poseStack.pushPose();
            poseStack.scale(side, side, side);
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(frame), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }

        Spot[] spots = spots(state.size);
        float scale = scale(state.size);
        for (int i = 0; i < spots.length; i++) {
            Spot spot = spots[i];
            Scene scene = SCENES.get(spot.scene());
            float time = (float) (Math.max(0, state.ownSeconds + i * SPOT_STAGGER) % loopLength(scene));
            poseStack.pushPose();
            poseStack.translate(spot.x() / 16, FLOOR_Y * side / 16, spot.z() / 16);
            poseStack.mulPose(Axis.YP.rotationDegrees(spot.yaw()));
            // Without power only the napper keeps its scene; the others curl up where they stand.
            submitScene(scene, time, state.showAwake || spot.scene() == NAP_SCENE, scale, state, poseStack, collector, models);
            poseStack.popPose();
        }

        poseStack.pushPose();
        poseStack.scale(side, side, side);
        submitGlass(state, poseStack, collector, models);
        poseStack.popPose();
    }

    /**
     * A scene with its middle here: its props, its Bitling and what it carries. A Bitling that isn't {@code awake} naps where
     * the scene starts it off, and the props wait as they are at the start.
     */
    private void submitScene(Scene scene, float time, boolean awake, float scale, State state, PoseStack poseStack,
            SubmitNodeCollector collector, ModelManager models) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(scene.turn()));
        poseStack.scale(scale, scale, scale);
        // The scene's models sit where the Bitling's do, its middle here.
        poseStack.translate(-(FEET_X + scene.x()) / 16, 0, -(FEET_X + scene.z()) / 16);

        BitlingAnimations sceneProps = props.get(scene.name());
        if (sceneProps != null) {
            Map<String, float[]> propPose = sceneProps.sample(sceneProps.clip(scene.loop()), awake ? time : 0);
            for (String part : scene.props()) {
                submitPart(models, propPath(scene, part), part, sceneProps, propPose, false, state, poseStack, collector);
            }
            for (String part : scene.seeThrough()) {
                submitPart(models, propPath(scene, part), part, sceneProps, propPose, true, state, poseStack, collector);
            }
        }

        Map<String, float[]> pose = awake ? animations.sample(animations.clip(scene.loop()), time) : napAtStart(scene, time);
        Map<String, StandaloneModelKey<BlockStateModelPart>> keys = ChipWorkshopRenderer.KEYS.get(BitlingKind.BASIC);
        for (String part : ChipWorkshopRenderer.PARTS) {
            // The chip is only ever in the Workshop's hand.
            if (part.equals("chip")) {
                continue;
            }
            submitPart(models.getStandaloneModel(keys.get(part)), part, animations, pose, false, state, poseStack, collector);
        }
        if (awake) {
            for (Held held : scene.held()) {
                submitPart(models, heldPath(held), held.bone(), animations, pose, false, state, poseStack, collector);
            }
        }
        poseStack.popPose();
    }

    /** The Workshop's napping loop, lying where and facing the way the scene's loop first puts the Bitling. */
    private Map<String, float[]> napAtStart(Scene scene, float time) {
        BitlingAnimations.Clip napping = animations.clip("napping");
        float length = napping == null || napping.length() <= 0 ? 1 : napping.length();
        Map<String, float[]> pose = animations.sample(napping, time % length);
        float[] start = animations.sample(animations.clip(scene.loop()), 0).get("root");
        float[] nap = pose.get("root");
        if (start != null && nap != null) {
            // The scene's spot and heading, with the nap's sink and tilt.
            pose.put("root", new float[] {start[0], nap[1], start[2], nap[3], start[4], nap[5], nap[6], nap[7], nap[8]});
        }
        return pose;
    }

    /** The glass, as clouded as the brain's state says. */
    private static void submitGlass(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        int stage = Mth.clamp(Math.round(state.glass * (GLASS_STAGES - 1)), 0, GLASS_STAGES - 1);
        BlockStateModelPart glass = models.getStandaloneModel(GLASS.get(stage));
        if (glass != null) {
            collector.submitBlockModel(poseStack, Sheets.translucentBlockItemSheet(), List.of(glass), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    private static void submitPart(ModelManager models, String path, String bone, BitlingAnimations moves, Map<String, float[]> pose,
            boolean seeThrough, State state, PoseStack poseStack, SubmitNodeCollector collector) {
        StandaloneModelKey<BlockStateModelPart> key = SCENE_MODELS.get(path);
        submitPart(key == null ? null : models.getStandaloneModel(key), bone, moves, pose, seeThrough, state, poseStack, collector);
    }

    private static void submitPart(@Nullable BlockStateModelPart model, String bone, BitlingAnimations moves, Map<String, float[]> pose,
            boolean seeThrough, State state, PoseStack poseStack, SubmitNodeCollector collector) {
        if (model == null) {
            return;
        }
        poseStack.pushPose();
        moves.pose(bone, pose, poseStack);
        collector.submitBlockModel(poseStack, seeThrough ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(), List.of(model),
                BlockModelRenderState.EMPTY_TINTS, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    public static class State extends BlockEntityRenderState {
        BrainSize size = BrainSize.SINGLE;
        boolean awake;
        boolean ready;
        Direction facing = Direction.NORTH;
        /** The cube's lowest corner from the brain, in blocks. */
        int originX;
        int originY;
        int originZ;
        double seconds;
        /** The time with this brain's own head start, so brains side by side don't move together. */
        double ownSeconds;
        /** When the brain last woke or fell asleep, in seconds; minus infinity if not since it came into view. */
        double wokeOrSlept = Double.NEGATIVE_INFINITY;
        /** Whether the Bitlings show awake: behind clouded glass they may still be in their old state. */
        boolean showAwake;
        /** How solid the glass is, from 0 to 1. */
        float glass;
        /** A single brain's scene, and seconds into it. */
        int scene;
        double sceneTime;
    }
}
