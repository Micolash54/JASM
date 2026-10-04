package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * Stands for a fluid where the Encoding Terminal keeps an example item: the slot holds this with the fluid on it,
 * and the screen draws the fluid instead. It is never given to a player.
 */
public class FluidMarkerItem extends Item {
    public FluidMarkerItem(Item.Properties properties) {
        super(properties);
    }

    /** A marker for {@code fluid}. */
    public static ItemStack of(FluidResource fluid) {
        ItemStack stack = new ItemStack(JasmItems.FLUID_MARKER.get());
        stack.set(JasmComponents.FLUID_MARKER.get(), fluid);
        return stack;
    }

    /** The fluid a stack stands for, or null if it is not a marker. */
    public static @Nullable FluidResource fluidOf(ItemStack stack) {
        return stack.is(JasmItems.FLUID_MARKER.get()) ? stack.get(JasmComponents.FLUID_MARKER.get()) : null;
    }

    public static boolean isMarker(ItemStack stack) {
        return fluidOf(stack) != null;
    }

    @Override
    public Component getName(ItemStack stack) {
        FluidResource fluid = fluidOf(stack);
        return fluid == null ? super.getName(stack) : fluid.getHoverName();
    }
}
