package dev.micolash.jasm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * The Bitling's parts and the loops it plays in the Chip Workshop, read from {@code animations/bitling.json} (made in
 * Blockbench). Keyframes blend like Blockbench's: smooth, straight or stepped.
 */
public final class BitlingAnimations {
    /** A moving part: where it turns, and the part it hangs from. */
    public record Bone(String name, @Nullable String parent, float[] pivot, boolean hasModel) {}

    record Key(float time, float x, float y, float z, String interpolation) {}

    /** Keyframes per part and channel ("rotation", "position", "scale"). */
    public record Clip(float length, Map<String, Map<String, List<Key>>> tracks) {}

    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final Map<String, Clip> clips = new LinkedHashMap<>();

    public static BitlingAnimations load(ResourceManager resources) {
        return load(resources, Jasm.id("animations/bitling.json"));
    }

    /** Parts and loops from any file in the same layout, like the props of a brain scene. */
    public static BitlingAnimations load(ResourceManager resources, Identifier file) {
        BitlingAnimations animations = new BitlingAnimations();
        try (Reader reader = resources.openAsReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bones").entrySet()) {
                JsonObject b = e.getValue().getAsJsonObject();
                JsonArray p = b.getAsJsonArray("pivot");
                String parent = b.get("parent").isJsonNull() ? null : b.get("parent").getAsString();
                animations.bones.put(e.getKey(), new Bone(e.getKey(), parent,
                        new float[]{p.get(0).getAsFloat(), p.get(1).getAsFloat(), p.get(2).getAsFloat()}, b.get("model").getAsBoolean()));
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
                JsonObject a = e.getValue().getAsJsonObject();
                Map<String, Map<String, List<Key>>> tracks = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> bone : a.getAsJsonObject("bones").entrySet()) {
                    Map<String, List<Key>> channels = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
                        List<Key> keys = new ArrayList<>();
                        for (JsonElement k : channel.getValue().getAsJsonArray()) {
                            JsonArray v = k.getAsJsonArray();
                            keys.add(new Key(v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat(), v.get(3).getAsFloat(),
                                    v.get(4).getAsString()));
                        }
                        channels.put(channel.getKey(), keys);
                    }
                    tracks.put(bone.getKey(), channels);
                }
                animations.clips.put(e.getKey(), new Clip(a.get("length").getAsFloat(), tracks));
            }
        } catch (Exception e) {
            Jasm.LOGGER.error("Could not read the animations in {}", file, e);
        }
        return animations;
    }

    public Map<String, Bone> bones() {
        return bones;
    }

    public @Nullable Clip clip(String name) {
        return clips.get(name);
    }

    private static final float[] ZERO = {0, 0, 0};
    private static final float[] ONE = {1, 1, 1};

    /** Moves {@code part} and every part it hangs from, root first, to where the loop has them at {@code time}. */
    public void pose(String part, @Nullable Clip clip, float time, PoseStack poseStack) {
        for (Bone bone : chain(part)) {
            move(bone, boneAt(clip, bone.name(), time), poseStack);
        }
    }

    /** Like {@link #pose(String, Clip, float, PoseStack)}, but from a whole pose made by {@link #sample(Clip, float)}. */
    public void pose(String part, Map<String, float[]> pose, PoseStack poseStack) {
        for (Bone bone : chain(part)) {
            float[] values = pose.get(bone.name());
            move(bone, values == null ? REST : values, poseStack);
        }
    }

    /**
     * Where every part is at {@code time} seconds into a loop: for each part its move, turn and size, nine numbers. Two of
     * these can be blended with {@link #mix}, so one loop eases out of wherever the last one really was.
     */
    public Map<String, float[]> sample(@Nullable Clip clip, float time) {
        Map<String, float[]> pose = new LinkedHashMap<>();
        for (String bone : bones.keySet()) {
            pose.put(bone, boneAt(clip, bone, time));
        }
        return pose;
    }

    /** Part of the way ({@code blend} from 0 to 1) from one pose to another. */
    public static Map<String, float[]> mix(Map<String, float[]> from, Map<String, float[]> to, float blend) {
        Map<String, float[]> pose = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> e : to.entrySet()) {
            float[] a = from.getOrDefault(e.getKey(), REST);
            float[] b = e.getValue();
            float[] v = new float[9];
            for (int i = 0; i < 9; i++) {
                v[i] = a[i] + (b[i] - a[i]) * blend;
            }
            pose.put(e.getKey(), v);
        }
        return pose;
    }

    private List<Bone> chain(String part) {
        List<Bone> chain = new ArrayList<>();
        for (Bone bone = bones.get(part); bone != null; bone = bone.parent() == null ? null : bones.get(bone.parent())) {
            chain.addFirst(bone);
        }
        return chain;
    }

    /** No move, no turn, full size. */
    private static final float[] REST = {0, 0, 0, 0, 0, 0, 1, 1, 1};

    private static float[] boneAt(@Nullable Clip clip, String bone, float time) {
        float[] move = sample(clip, bone, "position", time, ZERO);
        float[] turn = sample(clip, bone, "rotation", time, ZERO);
        float[] size = sample(clip, bone, "scale", time, ONE);
        return new float[]{move[0], move[1], move[2], turn[0], turn[1], turn[2], size[0], size[1], size[2]};
    }

    private static void move(Bone bone, float[] v, PoseStack poseStack) {
        float[] pivot = bone.pivot();
        poseStack.translate(v[0] / 16, v[1] / 16, v[2] / 16);
        poseStack.translate(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
        poseStack.mulPose(Axis.ZP.rotationDegrees(v[5]));
        poseStack.mulPose(Axis.YP.rotationDegrees(v[4]));
        poseStack.mulPose(Axis.XP.rotationDegrees(v[3]));
        // A part shrunk to nothing, like the chip before it is picked up, is simply not there.
        poseStack.scale(Math.max(v[6], 1e-4F), Math.max(v[7], 1e-4F), Math.max(v[8], 1e-4F));
        poseStack.translate(-pivot[0] / 16, -pivot[1] / 16, -pivot[2] / 16);
    }

    /** A part's value on one channel at {@code time} seconds into the loop, or {@code fallback} if it has no keys. */
    public static float[] sample(@Nullable Clip clip, String bone, String channel, float time, float[] fallback) {
        if (clip == null) {
            return fallback;
        }
        Map<String, List<Key>> channels = clip.tracks().get(bone);
        List<Key> keys = channels == null ? null : channels.get(channel);
        if (keys == null || keys.isEmpty()) {
            return fallback;
        }
        if (time <= keys.getFirst().time()) {
            return values(keys.getFirst());
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            Key a = keys.get(i);
            Key b = keys.get(i + 1);
            if (time > b.time()) {
                continue;
            }
            float span = b.time() - a.time();
            float t = span <= 0 ? 1 : (time - a.time()) / span;
            if (a.interpolation().equals("step")) {
                return values(a);
            }
            if (a.interpolation().equals("catmullrom") || b.interpolation().equals("catmullrom")) {
                Key before = i > 0 ? keys.get(i - 1) : a;
                Key after = i + 2 < keys.size() ? keys.get(i + 2) : b;
                return new float[]{catmullRom(before.x(), a.x(), b.x(), after.x(), t), catmullRom(before.y(), a.y(), b.y(), after.y(), t),
                        catmullRom(before.z(), a.z(), b.z(), after.z(), t)};
            }
            return new float[]{a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t};
        }
        return values(keys.getLast());
    }

    private static float[] values(Key k) {
        return new float[]{k.x(), k.y(), k.z()};
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5F * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }
}
