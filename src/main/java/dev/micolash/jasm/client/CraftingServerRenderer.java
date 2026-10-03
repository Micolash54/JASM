package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftingServerBlockEntity;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws a Crafting Server's parts in their bays, Processors in the left column and Storage Modules in the right, top
 * slot first like the screen, and the turning fans on its back while it has power. Each part model sits in the top
 * bay and is moved down to its row.
 */
public class CraftingServerRenderer implements BlockEntityRenderer<CraftingServerBlockEntity, CraftingServerRenderer.State> {
    private static final String[] PROCESSOR_NAMES = {"processor_basic", "processor_advanced", "processor_elite"};
    private static final String[] MODULE_NAMES = {"module_1k", "module_4k", "module_16k", "module_64k"};
    private static final List<StandaloneModelKey<BlockStateModelPart>> PROCESSORS = keys(PROCESSOR_NAMES);
    private static final List<StandaloneModelKey<BlockStateModelPart>> MODULES = keys(MODULE_NAMES);
    private static final StandaloneModelKey<BlockStateModelPart> FANS = new StandaloneModelKey<>(() -> "jasm:crafting_server/fans");
    /** Distance between two bays, in blocks. */
    private static final float ROW_STEP = 3 / 16F;
    private static final int ROWS = 4;

    public CraftingServerRenderer(BlockEntityRendererProvider.Context context) {}

    private static List<StandaloneModelKey<BlockStateModelPart>> keys(String[] names) {
        return Arrays.stream(names).map(name -> new StandaloneModelKey<BlockStateModelPart>(() -> "jasm:crafting_server/" + name)).toList();
    }

    static void registerModels(ModelEvent.RegisterStandalone event) {
        for (int i = 0; i < PROCESSOR_NAMES.length; i++) {
            event.register(PROCESSORS.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(model(PROCESSOR_NAMES[i])));
        }
        for (int i = 0; i < MODULE_NAMES.length; i++) {
            event.register(MODULES.get(i), SimpleUnbakedStandaloneModel.simpleModelWrapper(model(MODULE_NAMES[i])));
        }
        event.register(FANS, SimpleUnbakedStandaloneModel.simpleModelWrapper(model("fans")));
    }

    private static Identifier model(String name) {
        return Jasm.id("block/crafting_server/" + name);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            CraftingServerBlockEntity server, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(server, state, partialTicks, cameraPosition, breakProgress);
        state.facing = server.getBlockState().getValue(MachineBlock.FACING);
        for (int row = 0; row < ROWS; row++) {
            state.processors[row] = server.shownProcessor(row);
            state.modules[row] = server.shownModule(row);
        }
        state.fansTurning = server.shownRunning();
        // The server is solid, so its own spot is dark: light the parts from the block in front and the fans from
        // the block behind.
        Level level = server.getLevel();
        if (level != null) {
            state.lightCoords = LevelRenderer.getLightCoords(level, server.getBlockPos().relative(state.facing));
            state.backLight = LevelRenderer.getLightCoords(level, server.getBlockPos().relative(state.facing.getOpposite()));
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
            poseStack.pushPose();
            poseStack.translate(0, -row * ROW_STEP, 0);
            if (processor != null) {
                draw(models.getStandaloneModel(PROCESSORS.get(processor.ordinal())), poseStack, collector, state.lightCoords);
            }
            if (module != null) {
                draw(models.getStandaloneModel(MODULES.get(module.ordinal())), poseStack, collector, state.lightCoords);
            }
            poseStack.popPose();
        }
        // Still fans are part of the block's own picture; turning ones are drawn over them.
        if (state.fansTurning) {
            draw(models.getStandaloneModel(FANS), poseStack, collector, state.backLight);
        }
        poseStack.popPose();
    }

    private static void draw(@Nullable BlockStateModelPart part, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        if (part != null) {
            collector.submitBlockModel(
                    poseStack, Sheets.cutoutBlockItemSheet(), List.of(part), BlockModelRenderState.EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        final @Nullable ProcessorTier[] processors = new ProcessorTier[ROWS];
        final @Nullable MemoryTier[] modules = new MemoryTier[ROWS];
        boolean fansTurning;
        int backLight;
    }
}
