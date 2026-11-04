package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BitlingKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * The little scenes a Network Brain's Bitlings play, their props and loops, and how one is drawn. The Network Tether's
 * operator works at the desk scene.
 */
final class BrainScenes {
    /**
     * One scene: its loop and props, where its middle is (from the Bitling's feet, in model pixels), how far it is turned
     * so it reads best from the front, and what the Bitling carries. The see-through props, like a hologram, are drawn last.
     */
    record Scene(String name, float x, float z, float turn, List<String> props, List<String> seeThrough, List<Held> held) {
        String loop() {
            return "brain_" + name;
        }
    }

    /** Something the Bitling carries all through a scene: its model, and the part it hangs from. */
    record Held(String model, String bone) {}

    static final List<Scene> SCENES = List.of(
            new Scene("shapes", 0, -1.45F, 0, List.of("base", "box", "block_1", "block_2", "block_3", "ball"), List.of(), List.of()),
            new Scene("desk", 0.5F, -1, 0, List.of("desk", "juice"), List.of("holo"), List.of()),
            new Scene("nap", 0, 0.35F, 0, List.of("bag", "pillow", "zzz"), List.of(), List.of()),
            new Scene("crystal", 2, -0.2F, 0, List.of("stand", "crystal"), List.of(), List.of(new Held("pad", "pad"))),
            new Scene("doodle", 3, -1.7F, -116, List.of("easel"), List.of(), List.of(new Held("toy_marker", "toy"))),
            new Scene("snack", 0, -2.9F, 0, List.of("snack"), List.of(), List.of(new Held("toy_shard", "toy"))));
    /** The desk, with its juice box and hologram screen. */
    static final Scene DESK = SCENES.get(1);
    /** How fast the power pole of a brain floor turns. */
    static final float PILLAR_DEGREES_PER_SECOND = 30;
    /** Full brightness, for the pole's glow. */
    static final int GLOW = 0xF000F0;
    /** The Bitling model's own feet centre. */
    static final float FEET_X = 8;

    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> MODELS = new HashMap<>();

    static {
        for (Scene scene : SCENES) {
            for (String part : scene.props()) model(propPath(scene, part));
            for (String part : scene.seeThrough()) model(propPath(scene, part));
            for (Held held : scene.held()) model(heldPath(held));
        }
    }

    private static String propPath(Scene scene, String part) {
        return "block/brain_prop/" + scene.name() + "/" + part;
    }

    static String heldPath(Held held) {
        return "block/bitling/" + held.model();
    }

    private static void model(String path) {
        Identifier id = Jasm.id(path);
        MODELS.computeIfAbsent(path, p -> new StandaloneModelKey<>(id::toString));
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        for (Map.Entry<String, StandaloneModelKey<BlockStateModelPart>> e : MODELS.entrySet()) {
            event.register(e.getValue(), SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id(e.getKey())));
        }
    }

    private final BitlingAnimations animations;
    /** Each scene's props: their parts and how they move. */
    private final Map<String, BitlingAnimations> props = new HashMap<>();

    BrainScenes(ResourceManager resources) {
        animations = BitlingAnimations.load(resources);
        for (Scene scene : SCENES) {
            props.put(scene.name(), BitlingAnimations.load(resources, Jasm.id("animations/brain/" + scene.name() + ".json")));
        }
    }

    /** The Bitling's parts and loops. */
    BitlingAnimations animations() {
        return animations;
    }

    float loopLength(Scene scene) {
        BitlingAnimations.Clip clip = animations.clip(scene.loop());
        return clip == null || clip.length() <= 0 ? 30 : clip.length();
    }

    /** The Bitling's pose {@code time} seconds into the scene's loop. */
    Map<String, float[]> pose(Scene scene, float time) {
        return animations.sample(animations.clip(scene.loop()), time);
    }

    /** Moves into the frame of a scene whose middle is here, turned {@code heading} degrees from the front. */
    static void enter(Scene scene, float scale, float heading, PoseStack poseStack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(heading + scene.turn()));
        poseStack.scale(scale, scale, scale);
        // The scene's models sit where the Bitling's do, its middle here.
        poseStack.translate(-(FEET_X + scene.x()) / 16, 0, -(FEET_X + scene.z()) / 16);
    }

    /** The scene's props as they are {@code time} seconds into its loop, and the see-through ones after them if asked. */
    void submitProps(Scene scene, float time, boolean seeThrough, int light, PoseStack poseStack, SubmitNodeCollector collector,
            ModelManager models) {
        BitlingAnimations sceneProps = props.get(scene.name());
        if (sceneProps == null) {
            return;
        }
        Map<String, float[]> propPose = sceneProps.sample(sceneProps.clip(scene.loop()), time);
        for (String part : scene.props()) {
            submitPart(models, propPath(scene, part), part, sceneProps, propPose, false, light, poseStack, collector);
        }
        if (seeThrough) {
            for (String part : scene.seeThrough()) {
                submitPart(models, propPath(scene, part), part, sceneProps, propPose, true, light, poseStack, collector);
            }
        }
    }

    /** A Bitling of {@code kind} in {@code pose}, leaving out the chip (only ever in the Workshop's hand) and any part in {@code skip}. */
    void submitBitling(BitlingKind kind, Map<String, float[]> pose, Set<String> skip, int light, PoseStack poseStack,
            SubmitNodeCollector collector, ModelManager models) {
        Map<String, StandaloneModelKey<BlockStateModelPart>> keys = ChipWorkshopRenderer.KEYS.get(kind);
        for (String part : ChipWorkshopRenderer.PARTS) {
            if (part.equals("chip") || skip.contains(part)) {
                continue;
            }
            submitPart(models.getStandaloneModel(keys.get(part)), part, animations, pose, false, light, poseStack, collector);
        }
    }

    /** What the Bitling carries in the scene, posed with it. */
    void submitHeld(Scene scene, Map<String, float[]> pose, int light, PoseStack poseStack, SubmitNodeCollector collector, ModelManager models) {
        for (Held held : scene.held()) {
            submitPart(models, heldPath(held), held.bone(), animations, pose, false, light, poseStack, collector);
        }
    }

    private static void submitPart(ModelManager models, String path, String bone, BitlingAnimations moves, Map<String, float[]> pose,
            boolean seeThrough, int light, PoseStack poseStack, SubmitNodeCollector collector) {
        StandaloneModelKey<BlockStateModelPart> key = MODELS.get(path);
        submitPart(key == null ? null : models.getStandaloneModel(key), bone, moves, pose, seeThrough, light, poseStack, collector);
    }

    /** One model, moved with {@code bone} as {@code pose} has it. */
    static void submitPart(@Nullable BlockStateModelPart model, String bone, BitlingAnimations moves, Map<String, float[]> pose,
            boolean seeThrough, int light, PoseStack poseStack, SubmitNodeCollector collector) {
        if (model == null) {
            return;
        }
        poseStack.pushPose();
        moves.pose(bone, pose, poseStack);
        collector.submitBlockModel(poseStack, seeThrough ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(), List.of(model),
                BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
