package dev.micolash.jasm.wafer;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckWafers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * A wafer that holds one fluid without end. In a Deck it always shows the same fixed amount, gives as much as is asked
 * for, and deletes whatever of its fluid is put in. It keeps no data of its own: nothing to save, back up or lose.
 */
public class InfiniteWaferItem extends Item {
    /** What the Deck shows for the fluid, whatever is taken or put in: a billion buckets. */
    public static final long AMOUNT = 1_000_000_000L * FluidAmounts.PER_BUCKET;

    private final Fluid fluid;

    public InfiniteWaferItem(Item.Properties properties, Fluid fluid) {
        super(properties.stacksTo(1));
        this.fluid = fluid;
    }

    public Fluid fluid() {
        return fluid;
    }

    /** The fluid as stored: plain, with no extra data. */
    public FluidResource resource() {
        return FluidResource.of(fluid);
    }

    /** Wafers must never nest inside bundles, shulker boxes, or other container items. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    /** Whether {@code stack} goes in a Deck's wafer slot: a normal wafer or an infinite one. */
    public static boolean fitsWaferSlot(ItemStack stack) {
        return stack.getItem() instanceof WaferItem || stack.getItem() instanceof InfiniteWaferItem;
    }

    /** The fluids the infinite wafers in the Deck's usable slots stand for, each once. Extra slots waiting to be emptied don't count. */
    public static List<FluidResource> in(ItemStack deck) {
        if (!(deck.getItem() instanceof DeckItem item)) return List.of();
        int slots = item.tier().slots();
        List<FluidResource> fluids = List.of();
        for (DeckWafers.Entry entry : DeckItem.wafers(deck).entries()) {
            if (entry.slot() < slots && entry.wafer().item().value() instanceof InfiniteWaferItem infinite) {
                if (fluids.isEmpty()) fluids = new ArrayList<>(2);
                if (!fluids.contains(infinite.resource())) fluids.add(infinite.resource());
            }
        }
        return fluids;
    }

    /** Whether the Deck has an infinite wafer for exactly {@code key}. */
    public static boolean covers(ItemStack deck, FluidResource key) {
        return !key.isEmpty() && in(deck).contains(key);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.infinite_wafer.amount", resource().getHoverName()).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.jasm.infinite_wafer.deletes").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.jasm.infinite_wafer.no_backup").withStyle(ChatFormatting.DARK_GRAY));
    }
}
