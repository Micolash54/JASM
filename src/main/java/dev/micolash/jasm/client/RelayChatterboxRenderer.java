package dev.micolash.jasm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.relay.RelayChatterboxBlockEntity;
import dev.micolash.jasm.relay.RelayDish;
import dev.micolash.jasm.relay.RelayEnd;
import dev.micolash.jasm.relay.RelayStatus;
import java.io.Reader;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws what changes in the Relay Chatterbox's room: the status light and number on the front console, the
 * switchboard's lamps, and the operator at work. The operator plays one 30 second loop made in Blockbench, on the
 * same clock in every Chatterbox; with no operator inside, the stool stands empty, the lamps are dark, the data screen
 * is black and the cord hangs still. The status light glows in the dark unless it is off. On the roof, the dish's yoke
 * turns on its base and the bowl tilts on the yoke, moved by {@link RelayDish}: pointing at the partner while linked,
 * straight up when the partner is in another dimension, searching while it waits, at rest with no frequency. The room
 * and the dish's base are the block's own model.
 */
public class RelayChatterboxRenderer implements BlockEntityRenderer<RelayChatterboxBlockEntity, RelayChatterboxRenderer.State> {
    /** The operator's moving parts, in drawing order. */
    static final String[] OPERATOR = {"leg_r", "foot_r", "leg_l", "foot_l", "body", "arm_r", "arm_l", "head", "headset", "antenna"};
    /** The plug it moves between sockets, and the cord that shows while it is moved over. */
    static final String CORD = "cord_end";
    static final String CORD_FILL = "cord_fill";
    static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "dash"};
    /** How far apart the console's three digits sit, in model pixels. */
    private static final float DIGIT_STEP = 2.1F;
    private static final float LOOP_TICKS = 600;

    static final Map<String, StandaloneModelKey<BlockStateModelPart>> KEYS = new LinkedHashMap<>();
    static final Map<RelayStatus.Light, StandaloneModelKey<BlockStateModelPart>> LIGHTS = new EnumMap<>(RelayStatus.Light.class);

    static {
        for (String part : OPERATOR) key("operator/" + part);
        key("operator/" + CORD);
        key("operator/" + CORD_FILL);
        for (String digit : DIGITS) key("digit_" + digit);
        key("board_lit");
        key("board_dark");
        key("screen_on");
        key("screen_off");
        key("dish_yoke");
        key("dish_bowl");
        for (RelayStatus.Light light : RelayStatus.Light.values()) {
            LIGHTS.put(light, key("light_" + light.name().toLowerCase(Locale.ROOT)));
        }
    }

    private static StandaloneModelKey<BlockStateModelPart> key(String name) {
        String id = Jasm.id("block/relay_chatterbox/" + name).toString();
        return KEYS.computeIfAbsent(name, n -> new StandaloneModelKey<>(() -> id));
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        KEYS.forEach((name, key) -> event.register(key, SimpleUnbakedStandaloneModel.simpleModelWrapper(Jasm.id("block/relay_chatterbox/" + name))));
    }

    private final BitlingAnimations animations;
    private final BitlingAnimations.@Nullable Clip loop;
    /** Every Chatterbox shows the same moment of the loop, so the pose is worked out once a frame and shared. */
    private float posedAt = Float.NaN;
    private Map<String, float[]> pose = Map.of();
    private final Map<String, float[]> rest;
    private final RelayDish.Shape dish;

    public RelayChatterboxRenderer(BlockEntityRendererProvider.Context context) {
        animations = BitlingAnimations.load(Minecraft.getInstance().getResourceManager(), Jasm.id("animations/relay_operator.json"));
        loop = animations.clip("operator_loop");
        rest = animations.sample(null, 0);
        dish = loadDish();
    }

    /** The dish's pivots and tilt limits, written with its model. */
    private static RelayDish.Shape loadDish() {
        Identifier file = Jasm.id("animations/relay_dish.json");
        try (Reader reader = Minecraft.getInstance().getResourceManager().openAsReader(file)) {
            JsonObject d = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray axis = d.getAsJsonArray("turn_axis");
            JsonArray hinge = d.getAsJsonArray("hinge");
            return new RelayDish.Shape(axis.get(0).getAsFloat(), axis.get(1).getAsFloat(), hinge.get(0).getAsFloat(), hinge.get(1).getAsFloat(),
                    hinge.get(2).getAsFloat(), d.get("bowl_drop").getAsFloat(), d.get("rest_tilt").getAsFloat(), d.get("lowest_tilt").getAsFloat(),
                    d.get("front_lowest_tilt").getAsFloat(), d.get("front_turn").getAsFloat(), d.get("blend_turn").getAsFloat(),
                    d.get("highest_tilt").getAsFloat());
        } catch (Exception e) {
            Jasm.LOGGER.error("Could not read the dish in {}", file, e);
            // the dish then stays at its rest pose
            return new RelayDish.Shape(8, 8, 8, 25.3F, 6.9F, 16, 26, 26, 26, 10, 120, 26);
        }
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(RelayChatterboxBlockEntity relay, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(relay, state, partialTicks, cameraPosition, breakProgress);
        state.facing = relay.getBlockState().getValue(MachineBlock.FACING);
        state.light = relay.shownLight();
        state.frequency = relay.shownFrequency();
        state.seated = relay.shownSeated();
        Level level = relay.getLevel();
        // One clock for all: the world's time, round the loop, never offset by place or restarted by a change.
        state.seconds = level == null ? 0 : (level.getGameTime() % (long) LOOP_TICKS + partialTicks) / 20F;
        moveDish(relay, state, level == null ? 0 : (level.getGameTime() + partialTicks) / 20.0);
    }

    /** Moves the dish on to this frame: what it should do comes from the front, the partner only from what was sent. */
    private void moveDish(RelayChatterboxBlockEntity relay, State state, double seconds) {
        RelayDish moving = relay.dish();
        RelayDish.Mode mode = RelayDish.Mode.SEARCH;
        float turn = 0;
        float tilt = 0;
        if (state.light == RelayStatus.Light.OFF) {
            mode = RelayDish.Mode.REST;
        } else if (state.light == RelayStatus.Light.GREEN || state.light == RelayStatus.Light.YELLOW) {
            switch (relay.shownPartner()) {
                case ELSEWHERE -> mode = RelayDish.Mode.UP;
                case HERE -> {
                    mode = RelayDish.Mode.AIM;
                    long to = relay.shownPartnerPos();
                    BlockPos from = relay.getBlockPos();
                    float dx = BlockPos.getX(to) - from.getX();
                    float dy = BlockPos.getY(to) - from.getY();
                    float dz = BlockPos.getZ(to) - from.getZ();
                    // into the block's own frame, where the dish at rest faces -z
                    double facing = Math.toRadians(180 - state.facing.toYRot());
                    double x = dx * Math.cos(facing) - dz * Math.sin(facing);
                    double z = dx * Math.sin(facing) + dz * Math.cos(facing);
                    double across = Math.sqrt(x * x + z * z);
                    turn = across < 1e-3 ? moving.turn() : (float) Math.toDegrees(Math.atan2(-x, -z));
                    tilt = (float) Math.toDegrees(Math.atan2(dy, across));
                }
                case NONE -> mode = RelayDish.Mode.SEARCH;
            }
        }
        moving.update(seconds, mode, turn, tilt, dish);
        state.dishTurn = moving.turn();
        state.dishTilt = moving.tilt();
    }

    @Override
    public AABB getRenderBoundingBox(RelayChatterboxBlockEntity relay) {
        return relay.renderBox();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        draw(LIGHTS.get(state.light), poseStack, collector, state.light == RelayStatus.Light.OFF ? state.lightCoords : LightCoordsUtil.FULL_BRIGHT);
        String number = state.frequency == RelayEnd.NO_FREQUENCY ? null : String.format(Locale.ROOT, "%03d", state.frequency);
        for (int cell = 0; cell < 3; cell++) {
            poseStack.pushPose();
            poseStack.translate(-cell * DIGIT_STEP / 16, 0, 0);
            draw(KEYS.get("digit_" + (number == null ? "dash" : String.valueOf(number.charAt(cell)))), poseStack, collector, state.lightCoords);
            poseStack.popPose();
        }
        draw(KEYS.get(state.seated ? "board_lit" : "board_dark"), poseStack, collector, state.lightCoords);
        draw(KEYS.get(state.seated ? "screen_on" : "screen_off"), poseStack, collector, state.lightCoords);

        drawDish(state, poseStack, collector);

        Map<String, float[]> now = state.seated ? poseAt(state.seconds) : rest;
        drawPart(CORD, now, poseStack, collector, state);
        if (state.seated) {
            drawPart(CORD_FILL, now, poseStack, collector, state);
            for (String part : OPERATOR) {
                drawPart(part, now, poseStack, collector, state);
            }
        }
        poseStack.popPose();
    }

    /** The yoke turned about the block's centre, and the bowl tilted on the yoke's pins (its model sits a block low). */
    private void drawDish(State state, PoseStack poseStack, SubmitNodeCollector collector) {
        float axisX = dish.axisX() / 16;
        float axisZ = dish.axisZ() / 16;
        poseStack.pushPose();
        poseStack.translate(axisX, 0, axisZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.dishTurn));
        poseStack.translate(-axisX, 0, -axisZ);
        draw(KEYS.get("dish_yoke"), poseStack, collector, state.lightCoords);
        poseStack.translate(dish.hingeX() / 16, dish.hingeY() / 16, dish.hingeZ() / 16);
        poseStack.mulPose(Axis.XP.rotationDegrees(state.dishTilt));
        poseStack.translate(-dish.hingeX() / 16, (dish.bowlDrop() - dish.hingeY()) / 16, -dish.hingeZ() / 16);
        draw(KEYS.get("dish_bowl"), poseStack, collector, state.lightCoords);
        poseStack.popPose();
    }

    private Map<String, float[]> poseAt(float seconds) {
        float time = loop == null || loop.length() <= 0 ? 0 : seconds % loop.length();
        if (time != posedAt) {
            pose = animations.sample(loop, time);
            posedAt = time;
        }
        return pose;
    }

    private void drawPart(String part, Map<String, float[]> pose, PoseStack poseStack, SubmitNodeCollector collector, State state) {
        poseStack.pushPose();
        animations.pose(part, pose, poseStack);
        draw(KEYS.get("operator/" + part), poseStack, collector, state.lightCoords);
        poseStack.popPose();
    }

    private static void draw(@Nullable StandaloneModelKey<BlockStateModelPart> key, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        if (key == null) return;
        ModelManager models = Minecraft.getInstance().getModelManager();
        BlockStateModelPart model = models.getStandaloneModel(key);
        if (model != null) {
            collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), List.of(model), BlockModelRenderState.EMPTY_TINTS,
                    light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        RelayStatus.Light light = RelayStatus.Light.OFF;
        int frequency = RelayEnd.NO_FREQUENCY;
        boolean seated;
        float seconds;
        float dishTurn;
        float dishTilt;
    }
}
