package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.core.BitlingKind;
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
 * Draws the Bitlings busy inside a Network Brain. A lone brain plays one little scene after another; between scenes the glass
 * clouds over, the next scene is set out behind it, and the glass clears again. Without power its Bitling naps in the
 * armchair. A brain floor is one 3×3 room round a glowing core, with a Bitling on each of the 8 spots, each in a different
 * scene; at every change of scene the floor's glass clouds over and each Bitling moves on to the next one. Each floor keeps
 * its own clock. Without power they all curl up where they stand.
 */
public class NetworkBrainRenderer implements BlockEntityRenderer<NetworkBrainBlockEntity, NetworkBrainRenderer.State> {
    /** The middle of a floor: the still bases and rings, and the glowing pillar through them, which spins. */
    static final Identifier CORE_ID = Jasm.id("block/brain_core");
    static final StandaloneModelKey<BlockStateModelPart> CORE = new StandaloneModelKey<>(CORE_ID::toString);
    static final Identifier PILLAR_ID = Jasm.id("block/brain_core_pillar");
    static final StandaloneModelKey<BlockStateModelPart> PILLAR = new StandaloneModelKey<>(PILLAR_ID::toString);
    /** The slim frame and floor of a whole 3×3 room, three blocks wide. */
    static final Identifier ROOM_ID = Jasm.id("block/brain_frame");
    static final StandaloneModelKey<BlockStateModelPart> ROOM = new StandaloneModelKey<>(ROOM_ID::toString);

    /**
     * One scene of a lone brain: its loop and props, where its middle is (from the Bitling's feet, in model pixels), how far
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
    /** How big the Bitling and its props are drawn in a lone brain. */
    private static final float SCENE_SCALE = 0.5F;
    /** How big the Bitlings and props are drawn on a floor, and how far from the core their spots are, in blocks. */
    private static final float FLOOR_SCALE = 0.55F;
    private static final float SPOT_DISTANCE = 0.85F;
    /** The top of the brain's floor, in model pixels. */
    private static final float FLOOR_Y = 2;

    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> SCENE_MODELS = new HashMap<>();
    private static final List<StandaloneModelKey<BlockStateModelPart>> GLASS = new ArrayList<>();
    /** The glass of a whole floor, with a hole in its lid round the core. */
    private static final List<StandaloneModelKey<BlockStateModelPart>> FLOOR_GLASS = new ArrayList<>();

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
            Identifier floorId = Jasm.id("block/brain_glass_floor/stage_" + i);
            FLOOR_GLASS.add(new StandaloneModelKey<>(floorId::toString));
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
     * The 8 spots of a floor round its core, in blocks from the middle, going round from the front left. In each round of
     * scenes spot {@code i} plays {@code FLOOR_ORDER[(i + round) % 8]}, so the 8 always show different scenes.
     */
    private static final int[][] FLOOR_SPOTS = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
    /** The floor's 8 scenes. Until it has 8 of its own, the first two play twice, half a loop apart. */
    private static final int[] FLOOR_ORDER = {0, 1, 2, 3, 4, 5, 0, 1};
    /** Bitlings and props are only drawn this close, in blocks; further off a floor shows its room and core. */
    private static final double SCENE_DISTANCE = 32;
    /** How fast the core's pillar turns. */
    private static final float PILLAR_DEGREES_PER_SECOND = 30;
    /** Full brightness, for the core's glow. */
    private static final int GLOW = 0xF000F0;
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
        event.register(CORE, SimpleUnbakedStandaloneModel.simpleModelWrapper(CORE_ID));
        event.register(PILLAR, SimpleUnbakedStandaloneModel.simpleModelWrapper(PILLAR_ID));
        event.register(ROOM, SimpleUnbakedStandaloneModel.simpleModelWrapper(ROOM_ID));
        for (Map.Entry<String, StandaloneModelKey<BlockStateModelPart>> e : SCENE_MODELS.entrySet()) {
            event.register(e.getValue(), SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id(e.getKey())));
        }
        for (int i = 0; i < GLASS_STAGES; i++) {
            event.register(GLASS.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id("block/brain_glass/stage_" + i)));
            event.register(FLOOR_GLASS.get(i),
                    SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id("block/brain_glass_floor/stage_" + i)));
        }
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
        state.floor = block.hasProperty(NetworkBrainBlock.FLOOR) && block.getValue(NetworkBrainBlock.FLOOR);
        state.awake = block.hasProperty(NetworkBrainBlock.AWAKE) && block.getValue(NetworkBrainBlock.AWAKE);
        state.facing = block.hasProperty(MachineBlock.FACING) ? block.getValue(MachineBlock.FACING) : Direction.NORTH;
        BlockPos at = brain.getBlockPos();
        state.near = cameraPosition.distanceToSqr(Vec3.atCenterOf(at)) < SCENE_DISTANCE * SCENE_DISTANCE;
        Level level = brain.getLevel();
        if (level != null) {
            state.seconds = (level.getGameTime() + partialTicks) / 20.0;
            // A floor's middle is the brain itself, whose block draws nothing, so its light reaches the whole room.
            state.lightCoords = LevelRenderer.getLightCoords(level, at);
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
            pickScene(state);
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

    /** Which round of scenes the brain is in, how far into it, and how clouded its glass is. A lone brain shows one scene. */
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
        state.round = round;
        if (state.floor) {
            state.sceneTime = into;
            state.glass = Math.max(roundGlass, state.glass);
        } else if (state.showAwake) {
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

    /** Drawn even when the brain's own block is off screen, as long as part of its floor is in view. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(NetworkBrainBlockEntity brain) {
        BlockState block = brain.getBlockState();
        AABB own = new AABB(brain.getBlockPos());
        return block.hasProperty(NetworkBrainBlock.FLOOR) && block.getValue(NetworkBrainBlock.FLOOR) ? own.inflate(1, 0, 1) : own;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        // Turned the way the brain faces, like its block model.
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        if (state.floor) {
            submitFloor(state, poseStack, collector, models);
        } else {
            poseStack.translate(-0.5, 0, -0.5);
            submitSingle(state, poseStack, collector, models);
        }
        poseStack.popPose();
    }

    /** A lone brain's scene, then the glass around it. */
    private void submitSingle(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        Scene scene = SCENES.get(state.scene);
        poseStack.pushPose();
        poseStack.translate(0.5, FLOOR_Y / 16, 0.5);
        submitScene(scene, (float) (Math.max(0, state.sceneTime) % loopLength(scene)), true, SCENE_SCALE, 0, state, poseStack, collector,
                models);
        poseStack.popPose();
        submitGlass(GLASS, state, poseStack, collector, models);
    }

    /** A floor, drawn from its middle: the room's frame, the glowing core, the 8 scenes, then the glass. */
    private void submitFloor(State state, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        BlockStateModelPart room = models.getStandaloneModel(ROOM);
        BlockStateModelPart core = models.getStandaloneModel(CORE);
        BlockStateModelPart pillar = models.getStandaloneModel(PILLAR);
        poseStack.pushPose();
        poseStack.translate(-0.5, 0, -0.5);
        if (room != null) {
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(room), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
        if (core != null) {
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(core), BlockModelRenderState.EMPTY_TINTS, GLOW,
                    OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
        if (pillar != null) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees((float) (state.seconds * PILLAR_DEGREES_PER_SECOND % 360)));
            poseStack.translate(-0.5, 0, -0.5);
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(pillar), BlockModelRenderState.EMPTY_TINTS, GLOW,
                    OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }

        if (state.near) {
            for (int i = 0; i < FLOOR_SPOTS.length; i++) {
                int order = (int) Math.floorMod(i + state.round, (long) FLOOR_ORDER.length);
                Scene scene = SCENES.get(FLOOR_ORDER[order]);
                float length = loopLength(scene);
                // The second time a scene is on the floor, it plays half a loop behind the first.
                float behind = order >= SCENES.size() ? length / 2 : 0;
                float time = (float) (Math.max(0, state.sceneTime + behind) % length);
                poseStack.pushPose();
                poseStack.translate(FLOOR_SPOTS[i][0] * SPOT_DISTANCE, FLOOR_Y / 16, FLOOR_SPOTS[i][1] * SPOT_DISTANCE);
                // Each Bitling looks out from the core, towards the glass.
                float outward = (float) Math.toDegrees(Math.atan2(-FLOOR_SPOTS[i][0], -FLOOR_SPOTS[i][1]));
                submitScene(scene, time, state.showAwake, FLOOR_SCALE, outward, state, poseStack, collector, models);
                poseStack.popPose();
            }
        }

        poseStack.pushPose();
        poseStack.translate(-0.5, 0, -0.5);
        submitGlass(FLOOR_GLASS, state, poseStack, collector, models);
        poseStack.popPose();
    }

    /**
     * A scene with its middle here, turned {@code heading} degrees from the front: its props, its Bitling and what it carries.
     * A Bitling that isn't {@code awake} naps where the scene starts it off, and the props wait as they are at the start.
     */
    private void submitScene(Scene scene, float time, boolean awake, float scale, float heading, State state, PoseStack poseStack,
            SubmitNodeCollector collector, ModelManager models) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(heading + scene.turn()));
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
            pose.put("root", new float[]{start[0], nap[1], start[2], nap[3], start[4], nap[5], nap[6], nap[7], nap[8]});
        }
        return pose;
    }

    /** The glass, as clouded as the brain's state says. */
    private static void submitGlass(List<StandaloneModelKey<BlockStateModelPart>> stages, State state, PoseStack poseStack,
            SubmitNodeCollector collector, ModelManager models) {
        int stage = Mth.clamp(Math.round(state.glass * (GLASS_STAGES - 1)), 0, GLASS_STAGES - 1);
        BlockStateModelPart glass = models.getStandaloneModel(stages.get(stage));
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
        /** The middle of a brain floor. */
        boolean floor;
        boolean awake;
        /** Close enough for its Bitlings and props to be drawn. */
        boolean near;
        Direction facing = Direction.NORTH;
        double seconds;
        /** The time with this brain's own head start, so brains side by side don't move together. */
        double ownSeconds;
        /** When the brain last woke or fell asleep, in seconds; minus infinity if not since it came into view. */
        double wokeOrSlept = Double.NEGATIVE_INFINITY;
        /** Whether the Bitlings show awake: behind clouded glass they may still be in their old state. */
        boolean showAwake;
        /** How solid the glass is, from 0 to 1. */
        float glass;
        /** The round of scenes it is in. */
        long round;
        /** A lone brain's scene, and seconds into the round. */
        int scene;
        double sceneTime;
    }
}
