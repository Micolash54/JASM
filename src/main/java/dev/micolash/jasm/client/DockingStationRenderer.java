package dev.micolash.jasm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.micolash.jasm.dock.DockingStationBlockEntity;
import dev.micolash.jasm.network.MachineBlock;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws the docked Deck lying flat in the station's tray, as its item looks (kind, crafting module, charge bar), top edge
 * toward the back so it reads the right way up from the front.
 */
public class DockingStationRenderer implements BlockEntityRenderer<DockingStationBlockEntity, DockingStationRenderer.State> {
    /** The Deck is 10 pixels across, as wide as the tray floor leaves room for. */
    private static final float SIZE = 10F / 16;
    /** Its middle, in model pixels: centred, resting on the tray floor at 15 (half of its 10/16-pixel thickness above it). */
    private static final float MIDDLE_Y = 15 + SIZE / 2;

    private final ItemModelResolver itemModelResolver;

    public DockingStationRenderer(BlockEntityRendererProvider.Context context) {
        itemModelResolver = context.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DockingStationBlockEntity dock, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(dock, state, partialTicks, cameraPosition, breakProgress);
        state.facing = dock.getBlockState().getValue(MachineBlock.FACING);
        ItemStack deck = dock.shownDeck();
        if (deck.isEmpty()) {
            state.deck.clear();
        } else {
            itemModelResolver.updateForTopItem(state.deck, deck, ItemDisplayContext.NONE, dock.getLevel(), null, (int) dock.getBlockPos().asLong());
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.deck.isEmpty()) return;
        poseStack.pushPose();
        poseStack.translate(0.5F, MIDDLE_Y / 16, 0.5F);
        // The model faces north; turn with the block as its blockstate does.
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.facing.toYRot()));
        // Lay the item flat, picture up, its top edge toward the back.
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.scale(SIZE, SIZE, SIZE);
        state.deck.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        final ItemStackRenderState deck = new ItemStackRenderState();
    }
}
