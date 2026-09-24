package dev.micolash.jasm.wafer;

import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

/** An item that carries wafers inside itself (a Deck). Lets save code find every wafer a player holds. */
public interface WaferHolderItem {
    void forEachWafer(ItemStack holder, Consumer<ItemStack> action);
}
