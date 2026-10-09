package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AdvancedCraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.network.MachineBlock;
import java.util.Arrays;
import java.util.List;
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
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
import org.jspecify.annotations.Nullable;

/**
 * Draws an Advanced Crafting Server's parts in its ten rows, Processors on the left and Storage Modules on the right, top
 * row first like the screen; the turning fans up both sides while it has power; and on the front screen
 * what the job makes and what is crafting at that moment. Everything is drawn from the bottom half.
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
    private static final float NOW_X = 12.5F;
    private static final float TARGET_X = 4.75F;
    private static final float ITEMS_Y = 25.5F;
    private static final float NOW_SIZE = 4;
    private static final float TARGET_SIZE = 6;
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
        screenItem(state.target, TARGET_X, TARGET_SIZE, poseStack, collector);
        screenItem(state.now, NOW_X, NOW_SIZE, poseStack, collector);
        poseStack.popPose();
    }

    /** One item on the screen, flat like an icon and facing out of the front, at full brightness. */
    private static void screenItem(ItemStackRenderState item, float x, float size, PoseStack poseStack, SubmitNodeCollector collector) {
        if (item.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x / 16, ITEMS_Y / 16, ITEM_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(size / 16, size / 16, FLAT);
        item.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
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
    }
}
