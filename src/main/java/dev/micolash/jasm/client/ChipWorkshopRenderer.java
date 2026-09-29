package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws the Bitling in the Workshop's little room, standing on its step behind the counter. It plays one of three
 * loops made in Blockbench: working while it makes chips, napping while it recharges, idle otherwise. Each part is its
 * own model, turned around its joint every frame.
 */
public class ChipWorkshopRenderer implements BlockEntityRenderer<ChipWorkshopBlockEntity, ChipWorkshopRenderer.State> {
    /** The moving parts that have a model, in drawing order. */
    private static final String[] PARTS = {"leg_left", "leg_right", "body", "arm_left", "arm_right", "chip", "head", "eyes", "antenna"};
    private static final Map<BitlingKind, Map<String, StandaloneModelKey<BlockStateModelPart>>> KEYS = new EnumMap<>(BitlingKind.class);

    static {
        for (BitlingKind kind : BitlingKind.values()) {
            Map<String, StandaloneModelKey<BlockStateModelPart>> parts = new HashMap<>();
            for (String part : PARTS) {
                String id = modelId(kind, part).toString();
                parts.put(part, new StandaloneModelKey<>(() -> id));
            }
            KEYS.put(kind, parts);
        }
    }

    /** Where the Bitling stands in the room, and how small it is drawn there, in model pixels. */
    private static final float SPOT_X = 8;
    private static final float SPOT_Y = 4;
    private static final float SPOT_Z = 6.5F;
    private static final float SCALE = 0.47F;
    /** The Bitling model's own feet centre. */
    private static final float FEET_X = 8;
    private static final float FEET_Z = 7.75F;
    private static final float[] ZERO = {0, 0, 0};
    private static final float[] ONE = {1, 1, 1};

    private final BitlingAnimations animations;

    public ChipWorkshopRenderer(BlockEntityRendererProvider.Context context) {
        animations = BitlingAnimations.load(Minecraft.getInstance().getResourceManager());
    }

    private static Identifier modelId(BitlingKind kind, String part) {
        // The chip in its hand looks the same for every critter.
        if (kind == BitlingKind.BASIC || part.equals("chip")) {
            return Jasm.id("block/bitling/" + part);
        }
        return Jasm.id("block/bitling/" + kind.name().toLowerCase(Locale.ROOT) + "/" + part);
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        for (BitlingKind kind : BitlingKind.values()) {
            for (String part : PARTS) {
                event.register(KEYS.get(kind).get(part), SimpleUnbakedStandaloneModel.simpleModelWrapper(modelId(kind, part)));
            }
        }
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ChipWorkshopBlockEntity workshop, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(workshop, state, partialTicks, cameraPosition, breakProgress);
        state.facing = workshop.getBlockState().getValue(MachineBlock.FACING);
        state.looks = workshop.shownLooks();
        Level level = workshop.getLevel();
        if (level != null) {
            // Each Workshop is a little out of step with the next, so a row of them doesn't move as one.
            long offset = workshop.getBlockPos().asLong() * 7919L;
            state.seconds = ((level.getGameTime() + Math.floorMod(offset, 2000L)) + partialTicks) / 20F;
            // The room is open to the front, so it takes the light of the block in front.
            state.lightCoords = LightCoordsUtil.getLightCoords(level, workshop.getBlockPos().relative(state.facing));
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if ((state.looks & 1 << 6) == 0) {
            return;
        }
        BitlingKind kind = BitlingKind.values()[state.looks & 3];
        boolean napping = (state.looks & 1 << 4) != 0;
        boolean working = (state.looks & 1 << 5) != 0;
        BitlingAnimations.Clip clip = animations.clip(napping ? "napping" : working ? "working" : "idle");
        float time = clip == null || clip.length() <= 0 ? 0 : state.seconds % clip.length();

        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, 180 - state.facing.toYRot());
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        poseStack.translate(SPOT_X / 16, SPOT_Y / 16, SPOT_Z / 16);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-FEET_X / 16, 0, -FEET_Z / 16);
        for (String part : PARTS) {
            BlockStateModelPart model = models.getStandaloneModel(KEYS.get(kind).get(part));
            if (model == null) {
                continue;
            }
            poseStack.pushPose();
            pose(part, clip, time, poseStack);
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** Moves {@code part} and every part it hangs from, root first, to where the loop has them at {@code time}. */
    private void pose(String part, BitlingAnimations.@Nullable Clip clip, float time, PoseStack poseStack) {
        List<BitlingAnimations.Bone> chain = new ArrayList<>();
        for (BitlingAnimations.Bone bone = animations.bones().get(part); bone != null;
                bone = bone.parent() == null ? null : animations.bones().get(bone.parent())) {
            chain.addFirst(bone);
        }
        for (BitlingAnimations.Bone bone : chain) {
            float[] move = BitlingAnimations.sample(clip, bone.name(), "position", time, ZERO);
            float[] turn = BitlingAnimations.sample(clip, bone.name(), "rotation", time, ZERO);
            float[] size = BitlingAnimations.sample(clip, bone.name(), "scale", time, ONE);
            float[] pivot = bone.pivot();
            poseStack.translate(move[0] / 16, move[1] / 16, move[2] / 16);
            poseStack.translate(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
            poseStack.rotateDegrees(Axis.ZP, turn[2]);
            poseStack.rotateDegrees(Axis.YP, turn[1]);
            poseStack.rotateDegrees(Axis.XP, turn[0]);
            // A part shrunk to nothing, like the chip before it is picked up, is simply not there.
            poseStack.scale(Math.max(size[0], 1e-4F), Math.max(size[1], 1e-4F), Math.max(size[2], 1e-4F));
            poseStack.translate(-pivot[0] / 16, -pivot[1] / 16, -pivot[2] / 16);
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        int looks;
        float seconds;
    }
}
