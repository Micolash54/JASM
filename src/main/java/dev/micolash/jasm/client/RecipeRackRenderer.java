package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.Jasm;
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
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

/**
 * Draws a card in every filled slot of a Recipe Rack, with what the card makes on its face, drawn like its inventory
 * icon. The card model sits in the top-left slot; slot 0 to 3 run left to right along the top row, 4 to 7 the row below,
 * and so on, matching the rack's screen.
 *
 * <p>A rack the camera is behind draws nothing: its body hides the cards. Past the player's rack icon
 * distance the cards are drawn plain. The icons are worked out only when the rack's cards change, and kept between frames.
 */
public class RecipeRackRenderer implements BlockEntityRenderer<RecipeRackBlockEntity, RecipeRackRenderer.State> {
    public static final StandaloneModelKey<BlockStateModelPart> CARD_MODEL = new StandaloneModelKey<>(() -> "jasm:recipe_rack_card");
    public static final Identifier CARD_MODEL_ID = Jasm.id("block/recipe_rack_card");
    /** A 16-pixel inventory icon drawn 2.5 pixels across, covering the card face, and as deep as its shape. */
    private static final float ICON_SCALE = 2.5F / 16;
    /** The gap between a card's face and the back of what it shows, in pixels. */
    private static final float ICON_GAP = 0.1F;
    /** How far past the block the cards and a block's icon may reach: a full-depth icon stands about 3 pixels out. */
    private static final double REACH_OUT = 4 / 16.0;

    private final ItemModelResolver itemModelResolver;
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
     * Only racks in reach that the camera isn't behind. The cards and icons stand out of the front, so they can be seen
     * from beside the rack too: only a camera past the back face is sure to see none of them.
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
        state.icons = state.cards != 0 && iconsInReach(rack.getBlockPos(), cameraPosition) ? icons(rack) : null;
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
        ItemStackRenderState item = icons.items[slot];
        if (makes.isEmpty()) {
            if (item != null) item.clear();
            return;
        }
        if (item == null) {
            item = new ItemStackRenderState();
            icons.items[slot] = item;
        }
        itemModelResolver.updateForTopItem(item, makes, ItemDisplayContext.GUI, rack.getLevel(), null, seed + slot);
        // nothing to draw (a fluid or a material has no item model): the card shows plain
        if (item.isEmpty()) {
            return;
        }
        icons.shown |= 1 << slot;
        AABB box = item.getModelBoundingBox();
        float depth = (float) Math.max(0, box.maxZ - box.minZ) * ICON_SCALE;
        // the icon's back just in front of the card face, so flat items sit on it and blocks stand out at their full depth
        icons.middle[slot] = -(RecipeRackBlock.CARD_FACE + ICON_GAP) / 16 - depth / 2;
        icons.depthMiddle[slot] = (float) ((box.minZ + box.maxZ) / 2);
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
    private static void icon(Icons icons, int slot, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        ItemStackRenderState item = icons.items[slot];
        if (item == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(RecipeRackBlock.slotX(slot) / 16, RecipeRackBlock.slotY(slot) / 16, icons.middle[slot]);
        // an icon faces +z; the rack's front is -z
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
        poseStack.translate(0, 0, -icons.depthMiddle[slot]);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
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
        /** Where each icon's middle goes, in blocks along the rack as modelled facing north (out of the front is -z). */
        final float[] middle = new float[RecipeRackBlockEntity.SLOTS];
        /** The middle of each icon's own depth, in its own space, so it can be placed by its middle. */
        final float[] depthMiddle = new float[RecipeRackBlockEntity.SLOTS];

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
