package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AdvancedCraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.autocraft.ServerScreenMode;
import dev.micolash.jasm.network.MachineBlock;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * Draws an Advanced Crafting Server's parts in its ten rows, Processors on the left and Storage Modules on the right, top
 * row first like the screen; the turning fans up both sides while it has power; and on the front screen
 * what the job makes and what is crafting at that moment, in the info mode with the job's amount, progress, crafts running
 * and a core bar. Everything is drawn from the bottom half.
 *
 * <p>Places on the screen are in model pixels of a north-facing block: x grows to the viewer's left, y up from the
 * bottom of the lower half.
 */
public class AdvancedCraftingServerRenderer implements BlockEntityRenderer<AdvancedCraftingServerBlockEntity, AdvancedCraftingServerRenderer.State> {
    private static final String[] PROCESSOR_NAMES = {"processor_basic", "processor_advanced", "processor_elite"};
    private static final String[] MODULE_NAMES = {"module_1k", "module_4k", "module_16k", "module_64k"};
    private static final List<StandaloneModelKey<BlockStateModelPart>> PROCESSORS = keys(PROCESSOR_NAMES);
    private static final List<StandaloneModelKey<BlockStateModelPart>> MODULES = keys(MODULE_NAMES);
    /** The fans up both sides, each side lit from the blocks beside it. */
    private static final String[] FAN_NAMES = {"fans_east", "fans_west"};
    private static final List<StandaloneModelKey<BlockStateModelPart>> FANS = keys(FAN_NAMES);
    private static final int ROWS = AdvancedCraftingServerBlockEntity.PROCESSOR_SLOTS;
    /** Rows in the top half; the rest are in the bottom one. */
    private static final int UPPER_ROWS = 2;
    /** Distance between two rows, in blocks. */
    private static final float ROW_STEP = 2 / 16F;
    /** The screen's items, in pixels: centre and size. They sit just in front of the glass. */
    private static final float ITEM_Z = 0.95F / 16;
    /** The minimal screen: the two items, big. */
    private static final float NOW_X = 11.5F;
    private static final float TARGET_X = 4.5F;
    private static final float ITEMS_Y = 25;
    private static final float ITEM_SIZE = 5.5F;
    /** The info screen: smaller items, with room for the job's details. */
    private static final float INFO_NOW_X = 12.75F;
    private static final float INFO_TARGET_X = 7.5F;
    private static final float INFO_ITEMS_Y = 26.5F;
    private static final float INFO_ITEM_SIZE = 4;
    /** Glyphs, bars and frames sit between the glass and the items. */
    private static final float TEXT_Z = 0.98F / 16;
    /** One pixel of the screen's 64x64 texture, in model pixels. */
    private static final float DOT = 0.25F;
    private static final float GLYPH_W = 3 * DOT;
    private static final float GLYPH_H = 5 * DOT;
    /** Glyphs are 4 screen pixels apart. */
    private static final float ADVANCE = 4 * DOT;
    private static final float ARROW_X = 10.5F;
    private static final float ARROW_Y = 27;
    /** Amount and percent end at the same right edge. */
    private static final float RIGHT_EDGE = 1.5F;
    private static final float AMOUNT_Y = 28;
    private static final float PERCENT_Y = 26.25F;
    private static final float RUNNING_X = 12.25F;
    private static final float RUNNING_Y = 23.25F;
    private static final float BAR_X = 7;
    private static final float BAR_STEP = 3 * DOT;
    private static final float BAR_W = 2 * DOT;
    private static final float BAR_TOP = 23.25F;
    private static final float BAR_BOTTOM = 22;
    private static final int TEXT = 0xFFCDD6F4;
    private static final int GREEN = 0xFFA6E3A1;
    private static final int YELLOW = 0xFFF9E2AF;
    private static final int RED = 0xFFF38BA8;
    private static final int TEAL = 0xFF94E2D5;
    private static final int TEAL_DARK = 0xFF5E9E95;
    private static final int UNLIT = 0xFF313244;
    private static final int FLUID_FRAME = 0xFF45475A;
    /** The glyph sheet's cells. */
    private static final int SHEET = 32;
    private static final int CELL_W = 4;
    private static final int CELL_H = 6;
    private static final int CELLS_PER_ROW = 8;
    private static final Identifier GLYPHS = Jasm.id("advanced_crafting_server_screen_digits");
    /** How deep the flat items on the screen are. */
    private static final float FLAT = 0.01F;

    private final ItemModelResolver itemModelResolver;

    public AdvancedCraftingServerRenderer(BlockEntityRendererProvider.Context context) {
        itemModelResolver = context.itemModelResolver();
    }

    private static List<StandaloneModelKey<BlockStateModelPart>> keys(String[] names) {
        return Arrays.stream(names)
                .map(name -> new StandaloneModelKey<BlockStateModelPart>(() -> "jasm:advanced_crafting_server/" + name)).toList();
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        for (int i = 0; i < PROCESSOR_NAMES.length; i++) {
            event.register(PROCESSORS.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(model(PROCESSOR_NAMES[i])));
        }
        for (int i = 0; i < MODULE_NAMES.length; i++) {
            event.register(MODULES.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(model(MODULE_NAMES[i])));
        }
        for (int i = 0; i < FAN_NAMES.length; i++) {
            event.register(FANS.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(model(FAN_NAMES[i])));
        }
    }

    private static Identifier model(String name) {
        return Jasm.id("block/advanced_crafting_server/" + name);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            AdvancedCraftingServerBlockEntity server, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(server, state, partialTicks, cameraPosition, breakProgress);
        state.facing = server.getBlockState().getValue(MachineBlock.FACING);
        for (int row = 0; row < ROWS; row++) {
            state.processors[row] = server.shownProcessor(row);
            state.modules[row] = server.shownModule(row);
        }
        state.fansTurning = server.shownRunning();
        Level level = server.getLevel();
        int seed = (int) server.getBlockPos().asLong();
        item(state.target, server.shownTarget(), level, seed);
        item(state.now, server.shownNow(), level, seed + 1);
        state.targetFluid = fluid(server.shownTarget());
        state.nowFluid = fluid(server.shownNow());
        state.mode = server.shownMode();
        state.info = server.shownTarget().isEmpty() ? null : server.shownInfo();
        state.targetIsFluid = state.targetFluid != null;
        state.parallel = server.shownParallel();
        // Both halves are solid, so their own spots are dark: light each half's parts from the block in front of it, and
        // each face's fans from the brighter of the two blocks beside that face.
        if (level != null) {
            BlockPos lower = server.getBlockPos();
            BlockPos upper = lower.above();
            state.lightCoords = LevelRenderer.getLightCoords(level, lower.relative(state.facing));
            state.upperLight = LevelRenderer.getLightCoords(level, upper.relative(state.facing));
            Direction[] sides = {state.facing.getClockWise(), state.facing.getCounterClockWise()};
            for (int i = 0; i < sides.length; i++) {
                state.fanLight[i] = Math.max(LevelRenderer.getLightCoords(level, lower.relative(sides[i])),
                        LevelRenderer.getLightCoords(level, upper.relative(sides[i])));
            }
        }
    }

    private void item(ItemStackRenderState state, ItemStack stack, @Nullable Level level, int seed) {
        if (stack.isEmpty()) {
            state.clear();
        } else {
            itemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, level, null, seed);
        }
    }

    /** A fluid has no item model: the screen shows its still texture, tinted as in the world, like icons elsewhere. */
    private static @Nullable ScreenFluid fluid(ItemStack stack) {
        FluidResource fluid = FluidMarkerItem.fluidOf(stack);
        if (fluid == null) return null;
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.getFluid().defaultFluidState());
        var tint = model.fluidTintSource();
        int color = tint == null ? 0xFFFFFFFF : tint.colorAsStack(fluid.toStack(1)) | 0xFF000000;
        return new ScreenFluid(model.stillMaterial().sprite(), color);
    }

    record ScreenFluid(TextureAtlasSprite sprite, int color) {}

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ModelManager models = Minecraft.getInstance().getModelManager();
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        for (int row = 0; row < ROWS; row++) {
            ProcessorTier processor = state.processors[row];
            MemoryTier module = state.modules[row];
            if (processor == null && module == null) {
                continue;
            }
            int light = row < UPPER_ROWS ? state.upperLight : state.lightCoords;
            poseStack.pushPose();
            poseStack.translate(0, -row * ROW_STEP, 0);
            if (processor != null) {
                draw(models.getStandaloneModel(PROCESSORS.get(processor.ordinal())), poseStack, collector, light);
            }
            if (module != null) {
                draw(models.getStandaloneModel(MODULES.get(module.ordinal())), poseStack, collector, light);
            }
            poseStack.popPose();
        }
        // Still fans are part of the block's own picture; turning ones are drawn just over them.
        if (state.fansTurning) {
            for (int i = 0; i < FANS.size(); i++) {
                draw(models.getStandaloneModel(FANS.get(i)), poseStack, collector, state.fanLight[i]);
            }
        }
        boolean info = state.mode == ServerScreenMode.INFO;
        float y = info ? INFO_ITEMS_Y : ITEMS_Y;
        float size = info ? INFO_ITEM_SIZE : ITEM_SIZE;
        float targetX = info ? INFO_TARGET_X : TARGET_X;
        screenItem(state.target, state.targetFluid, targetX, y, size, poseStack, collector);
        screenItem(state.now, state.nowFluid, info ? INFO_NOW_X : NOW_X, y, size, poseStack, collector);
        boolean frame = state.targetFluid != null;
        AdvancedCraftingServerBlockEntity.Info details = info ? state.info : null;
        if (frame || details != null) {
            TextureAtlasSprite glyphs = Minecraft.getInstance().getAtlasManager().get(Sheets.BLOCKS_MAPPER.apply(GLYPHS));
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS), (pose, buffer) -> {
                Glyphs out = new Glyphs(pose, buffer, glyphs);
                if (frame) {
                    // a one-pixel outline round the fluid's square
                    float half = size / 2 + DOT;
                    out.outline(targetX + half, targetX - half, y + half, y - half, FLUID_FRAME);
                }
                if (details != null) {
                    details(out, details, state);
                }
            });
        }
        poseStack.popPose();
    }

    /** The info screen's arrow, amount, percent, crafts running and core bar. */
    private static void details(Glyphs out, AdvancedCraftingServerBlockEntity.Info info, State state) {
        int status = switch (info.status()) {
            case RUNNING -> GREEN;
            case PAUSED -> YELLOW;
            case STOPPED -> RED;
        };
        out.text(">", ARROW_X, ARROW_Y, status);
        out.textRight(ServerScreenText.amount(info.amount(), state.targetIsFluid), RIGHT_EDGE, AMOUNT_Y, TEXT);
        out.textRight(ServerScreenText.percent(info.percent()), RIGHT_EDGE, PERCENT_Y, status);
        out.text(ServerScreenText.running(info.running(), state.parallel), RUNNING_X, RUNNING_Y, TEXT);
        int lit = ServerScreenText.litSegments(info.running(), state.parallel);
        for (int i = 0; i < ServerScreenText.SEGMENTS; i++) {
            float left = BAR_X - i * BAR_STEP;
            if (i < lit) {
                out.fill(left, left - BAR_W, BAR_TOP, BAR_BOTTOM + DOT, TEAL);
                out.fill(left, left - BAR_W, BAR_BOTTOM + DOT, BAR_BOTTOM, TEAL_DARK);
            } else {
                out.fill(left, left - BAR_W, BAR_TOP, BAR_BOTTOM, UNLIT);
            }
        }
    }

    /** Draws the glyph sheet's characters and solid quads, flat on the screen and facing out of the front, full bright. */
    private record Glyphs(PoseStack.Pose pose, VertexConsumer buffer, TextureAtlasSprite sheet) {
        /** {@code left} is the glyphs' left edge as seen from the front. */
        void text(String text, float left, float top, int color) {
            for (int i = 0; i < text.length(); i++) {
                int glyph = ServerScreenText.glyph(text.charAt(i));
                if (glyph < 0) continue;
                float u = (glyph % CELLS_PER_ROW) * CELL_W;
                float v = (glyph / CELLS_PER_ROW) * CELL_H;
                float x = left - i * ADVANCE;
                quad(x, x - GLYPH_W, top, top - GLYPH_H, u(u), u(u + 3), v(v), v(v + 5), color);
            }
        }

        /** {@code right} is the last glyph's right edge as seen from the front. */
        void textRight(String text, float right, float top, int color) {
            text(text, right + GLYPH_W + (text.length() - 1) * ADVANCE, top, color);
        }

        /** A solid quad from the viewer's {@code left} to {@code right}. */
        void fill(float left, float right, float top, float bottom, int color) {
            int solid = ServerScreenText.solid();
            float u = u((solid % CELLS_PER_ROW) * CELL_W + 1.5F);
            float v = v((solid / CELLS_PER_ROW) * CELL_H + 2.5F);
            quad(left, right, top, bottom, u, u, v, v, color);
        }

        void outline(float left, float right, float top, float bottom, int color) {
            fill(left, right, top, top - DOT, color);
            fill(left, right, bottom + DOT, bottom, color);
            fill(left, left - DOT, top - DOT, bottom + DOT, color);
            fill(right + DOT, right, top - DOT, bottom + DOT, color);
        }

        private float u(float pixel) {
            return sheet.getU(pixel / SHEET);
        }

        private float v(float pixel) {
            return sheet.getV(pixel / SHEET);
        }

        /** Model pixels; the texture's left edge goes on the viewer's left, which is the larger x. */
        private void quad(float left, float right, float top, float bottom, float u0, float u1, float v0, float v1, int color) {
            corner(left / 16, bottom / 16, u0, v1, color);
            corner(right / 16, bottom / 16, u1, v1, color);
            corner(right / 16, top / 16, u1, v0, color);
            corner(left / 16, top / 16, u0, v0, color);
        }

        private void corner(float x, float y, float u, float v, int color) {
            buffer.addVertex(pose, x, y, TEXT_Z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, -1);
        }
    }

    /** One item (or fluid) on the screen, flat like an icon and facing out of the front, at full brightness. */
    private static void screenItem(ItemStackRenderState item, @Nullable ScreenFluid fluid, float x, float y, float size, PoseStack poseStack,
            SubmitNodeCollector collector) {
        if (item.isEmpty() && fluid == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x / 16, y / 16, ITEM_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(size / 16, size / 16, FLAT);
        if (fluid != null) {
            TextureAtlasSprite sprite = fluid.sprite();
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(sprite.atlasLocation()), (pose, buffer) -> {
                // the square an item fills, on its front face
                vertex(pose, buffer, -0.5F, -0.5F, sprite.getU0(), sprite.getV1(), fluid.color());
                vertex(pose, buffer, 0.5F, -0.5F, sprite.getU1(), sprite.getV1(), fluid.color());
                vertex(pose, buffer, 0.5F, 0.5F, sprite.getU1(), sprite.getV0(), fluid.color());
                vertex(pose, buffer, -0.5F, 0.5F, sprite.getU0(), sprite.getV0(), fluid.color());
            });
        } else {
            item.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float u, float v, int color) {
        buffer.addVertex(pose, x, y, 0.5F).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }

    private static void draw(@Nullable BlockStateModelPart part, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        if (part != null) {
            collector.submitBlockModel(
                    poseStack, Sheets.cutoutBlockItemSheet(), List.of(part), BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    /** Both halves. */
    @Override
    public AABB getRenderBoundingBox(AdvancedCraftingServerBlockEntity server) {
        return new AABB(server.getBlockPos()).expandTowards(0, 1, 0);
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        final @Nullable ProcessorTier[] processors = new ProcessorTier[ROWS];
        final @Nullable MemoryTier[] modules = new MemoryTier[ROWS];
        boolean fansTurning;
        int upperLight;
        /** Right and left, as the fan models are listed. */
        final int[] fanLight = new int[2];
        final ItemStackRenderState target = new ItemStackRenderState();
        final ItemStackRenderState now = new ItemStackRenderState();
        @Nullable ScreenFluid targetFluid;
        @Nullable ScreenFluid nowFluid;
        ServerScreenMode mode = ServerScreenMode.MINIMAL;
        AdvancedCraftingServerBlockEntity.@Nullable Info info;
        boolean targetIsFluid;
        /** Crafts the installed Processors run at once. */
        int parallel;
    }
}
