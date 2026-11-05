package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.RecipeRackBlock;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.network.MachineBlock;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.joml.Matrix3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a card in every filled slot of a Recipe Rack, with what the card makes on its face, drawn like its inventory
 * icon and laid flat on the card, a block's 3D picture squashed to no depth. A fluid shows its still texture, tinted as in
 * the world. The card model sits in the top-left slot; slot
 * 0 to 3 run left to right along the top row, 4 to 7 the row below, and so on, matching the rack's screen.
 *
 * <p>A rack the camera is behind draws nothing: its body hides the cards. Beside it, or past the player's rack icon
 * distance, the cards are drawn plain. The icons are worked out only when the rack's cards change, and kept between frames.
 */
public class RecipeRackRenderer implements BlockEntityRenderer<RecipeRackBlockEntity, RecipeRackRenderer.State> {
    public static final StandaloneModelKey<BlockStateModelPart> CARD_MODEL = new StandaloneModelKey<>(() -> "jasm:recipe_rack_card");
    public static final Identifier CARD_MODEL_ID = Jasm.id("block/recipe_rack_card");
    /** A 16-pixel inventory icon drawn across the card face. */
    private static final float ICON_SCALE = RecipeRackBlock.ICON_SIZE / 16;
    /** How far past the block the cards and the pictures on them reach: under a pixel. */
    private static final double REACH_OUT = 1 / 16.0;

    private final ItemModelResolver itemModelResolver;
    /** Kept between icons so drawing one makes nothing new. */
    private final Matrix3f normal = new Matrix3f();
    private @Nullable BlockStateModelPart card;
    private List<BlockStateModelPart> cardParts = List.of();

    public RecipeRackRenderer(BlockEntityRendererProvider.Context context) {
        itemModelResolver = context.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /**
     * Only racks in reach that the camera isn't behind. The cards stand half a pixel out of the front, so their edges show
     * from beside the rack; the pictures on them are skipped there (see {@link #facesCamera}).
     */
    @Override
    public boolean shouldRender(RecipeRackBlockEntity rack, Vec3 cameraPosition) {
        BlockPos pos = rack.getBlockPos();
        double dx = cameraPosition.x - (pos.getX() + 0.5);
        double dy = cameraPosition.y - (pos.getY() + 0.5);
        double dz = cameraPosition.z - (pos.getZ() + 0.5);
        int reach = getViewDistance();
        if (dx * dx + dy * dy + dz * dz >= reach * reach) {
            return false;
        }
        Direction facing = rack.getBlockState().getValue(MachineBlock.FACING);
        // in front of the back face
        return (dx + facing.getStepX() * 0.5) * facing.getStepX() + (dz + facing.getStepZ() * 0.5) * facing.getStepZ() > 0;
    }

    @Override
    public void extractRenderState(
            RecipeRackBlockEntity rack, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(rack, state, partialTicks, cameraPosition, breakProgress);
        state.facing = rack.getBlockState().getValue(MachineBlock.FACING);
        state.cards = 0;
        for (int slot = 0; slot < RecipeRackBlockEntity.SLOTS; slot++) {
            if (rack.showsCard(slot)) {
                state.cards |= 1 << slot;
            }
        }
        // The rack is solid, so its own spot is dark: light the cards from the block in front of it.
        if (rack.getLevel() != null) {
            state.lightCoords = LevelRenderer.getLightCoords(rack.getLevel(), rack.getBlockPos().relative(state.facing));
        }
        state.icons = state.cards != 0 && facesCamera(rack.getBlockPos(), state.facing, cameraPosition)
                && iconsInReach(rack.getBlockPos(), cameraPosition) ? icons(rack) : null;
    }

    /** Whether the camera is in front of the rack's front face: beside or behind it, the pictures on the cards can't be seen. */
    private static boolean facesCamera(BlockPos pos, Direction facing, Vec3 cameraPosition) {
        double dx = cameraPosition.x - (pos.getX() + 0.5 + facing.getStepX() * 0.5);
        double dz = cameraPosition.z - (pos.getZ() + 0.5 + facing.getStepZ() * 0.5);
        return dx * facing.getStepX() + dz * facing.getStepZ() > 0;
    }

    private static boolean iconsInReach(BlockPos pos, Vec3 cameraPosition) {
        int reach = JasmClientConfig.rackIconDistance();
        if (reach <= 0) {
            return false;
        }
        double dx = cameraPosition.x - (pos.getX() + 0.5);
        double dy = cameraPosition.y - (pos.getY() + 0.5);
        double dz = cameraPosition.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= (double) reach * reach;
    }

    /** The rack's block and a little round it, so cards and icons standing out of the front aren't cut off. */
    @Override
    public AABB getRenderBoundingBox(RecipeRackBlockEntity rack) {
        return kept(rack).bounds;
    }

    private Icons kept(RecipeRackBlockEntity rack) {
        if (rack.renderCache() instanceof Icons kept && kept.owner == this) {
            return kept;
        }
        // round every side, so a rack turned with a wrench needs nothing new
        Icons icons = new Icons(this, new AABB(rack.getBlockPos()).inflate(REACH_OUT, 0, REACH_OUT));
        rack.setRenderCache(icons);
        return icons;
    }

    /** The rack's icons, worked out again only when its cards changed or the resources were reloaded. */
    private Icons icons(RecipeRackBlockEntity rack) {
        Icons icons = kept(rack);
        if (icons.version != rack.shownVersion()) {
            icons.version = rack.shownVersion();
            int seed = HashCommon.long2int(rack.getBlockPos().asLong());
            for (int slot = 0; slot < RecipeRackBlockEntity.SLOTS; slot++) {
                resolve(icons, slot, rack.shownMakes(slot), rack, seed);
            }
        }
        return icons;
    }

    private void resolve(Icons icons, int slot, ItemStack makes, RecipeRackBlockEntity rack, int seed) {
        icons.shown &= ~(1 << slot);
        icons.fluids[slot] = null;
        ItemStackRenderState item = icons.items[slot];
        FluidResource fluid = FluidMarkerItem.fluidOf(makes);
        if (makes.isEmpty() || fluid != null) {
            if (item != null) item.clear();
            if (fluid != null) {
                // a fluid has no item model: its still texture, tinted as in the world, like the fluid in a slot
                FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.getFluid().defaultFluidState());
                var tint = model.fluidTintSource();
                icons.fluids[slot] = model.stillMaterial().sprite();
                icons.fluidColors[slot] = tint == null ? 0xFFFFFFFF : tint.colorAsStack(fluid.toStack(1)) | 0xFF000000;
                icons.shown |= 1 << slot;
            }
            return;
        }
        if (item == null) {
            item = new ItemStackRenderState();
            icons.items[slot] = item;
        }
        itemModelResolver.updateForTopItem(item, makes, ItemDisplayContext.GUI, rack.getLevel(), null, seed + slot);
        // nothing to draw (a material has no item model): the card shows plain
        if (item.isEmpty()) {
            return;
        }
        icons.shown |= 1 << slot;
        AABB box = item.getModelBoundingBox();
        // flat on the card: a block's 3D picture is squashed to a flat item's thickness, its back on the icon plane
        icons.depthScale[slot] = RecipeRackBlock.iconDepthScale(Math.max(0, box.maxZ - box.minZ)) / 16;
        icons.back[slot] = (float) box.minZ;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.cards == 0) {
            return;
        }
        BlockStateModelPart part = Minecraft.getInstance().getModelManager().getStandaloneModel(CARD_MODEL);
        if (part == null) {
            return;
        }
        if (part != card) {
            card = part;
            cardParts = List.of(part);
        }
        Icons icons = state.icons;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(RecipeRackBlock.yRotation(state.facing)));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        float cardX = RecipeRackBlock.slotX(0);
        float cardY = RecipeRackBlock.slotY(0);
        for (int slot = 0; slot < RecipeRackBlockEntity.SLOTS; slot++) {
            if ((state.cards & (1 << slot)) == 0) {
                continue;
            }
            poseStack.pushPose();
            // The card model sits in slot 0.
            poseStack.translate((RecipeRackBlock.slotX(slot) - cardX) / 16, (RecipeRackBlock.slotY(slot) - cardY) / 16, 0);
            collector.submitBlockModel(
                    poseStack, Sheets.cutoutBlockItemSheet(), cardParts, BlockModelRenderState.EMPTY_TINTS, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    0);
            poseStack.popPose();
            if (icons != null && (icons.shown & (1 << slot)) != 0) {
                icon(icons, slot, poseStack, collector, state.lightCoords);
            }
        }
        poseStack.popPose();
    }

    /** What the card in {@code slot} makes, on its face, facing out of the front. */
    private void icon(Icons icons, int slot, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        TextureAtlasSprite fluid = icons.fluids[slot];
        if (fluid != null) {
            fluidIcon(fluid, icons.fluidColors[slot], slot, poseStack, collector, light);
            return;
        }
        ItemStackRenderState item = icons.items[slot];
        if (item == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(RecipeRackBlock.slotX(slot) / 16, RecipeRackBlock.slotY(slot) / 16, -RecipeRackBlock.ICON_BACK / 16);
        // an icon faces +z; the rack's front is -z
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        // squashing only the depth would turn every face to the front: keep the faces lit as the unsquashed picture's
        normal.set(poseStack.last().normal());
        poseStack.scale(ICON_SCALE, ICON_SCALE, icons.depthScale[slot]);
        poseStack.last().normal().set(normal);
        poseStack.translate(0, 0, -icons.back[slot]);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /** The fluid the card in {@code slot} makes: a square the size of an item's icon, on the icon plane, facing out of the front. */
    private static void fluidIcon(TextureAtlasSprite sprite, int color, int slot, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        poseStack.pushPose();
        poseStack.translate(RecipeRackBlock.slotX(slot) / 16, RecipeRackBlock.slotY(slot) / 16, -RecipeRackBlock.ICON_BACK / 16);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(sprite.atlasLocation()), (pose, buffer) -> {
            vertex(pose, buffer, -0.5F, -0.5F, sprite.getU0(), sprite.getV1(), color, light);
            vertex(pose, buffer, 0.5F, -0.5F, sprite.getU1(), sprite.getV1(), color, light);
            vertex(pose, buffer, 0.5F, 0.5F, sprite.getU1(), sprite.getV0(), color, light);
            vertex(pose, buffer, -0.5F, 0.5F, sprite.getU0(), sprite.getV0(), color, light);
        });
        poseStack.popPose();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float u, float v, int color, int light) {
        buffer.addVertex(pose, x, y, 0).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, 0, 0, 1);
    }

    /** One rack's icons and bounds, kept on the rack between frames. A renderer made after a resource reload starts afresh. */
    private static final class Icons {
        final RecipeRackRenderer owner;
        final AABB bounds;
        /** The rack's {@link RecipeRackBlockEntity#shownVersion()} these were worked out for. */
        int version = Integer.MIN_VALUE;
        /** One bit per slot with an icon to draw. */
        int shown;
        final @Nullable ItemStackRenderState[] items = new ItemStackRenderState[RecipeRackBlockEntity.SLOTS];
        /** The still texture of the fluid each card makes, or null where it makes no fluid. */
        final @Nullable TextureAtlasSprite[] fluids = new TextureAtlasSprite[RecipeRackBlockEntity.SLOTS];
        /** Each fluid's tint. */
        final int[] fluidColors = new int[RecipeRackBlockEntity.SLOTS];
        /** Blocks per unit of each icon's depth: flat on the card. */
        final float[] depthScale = new float[RecipeRackBlockEntity.SLOTS];
        /** The back of each icon in its own space, laid on the icon plane. */
        final float[] back = new float[RecipeRackBlockEntity.SLOTS];

        Icons(RecipeRackRenderer owner, AABB bounds) {
            this.owner = owner;
            this.bounds = bounds;
        }
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        int cards;
        /** Null past the icon distance: plain cards. */
        @Nullable Icons icons;
    }
}
