package dev.micolash.jasm.bay;

import net.minecraft.core.Holder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;

/** Takes mining enchantments like a pickaxe, but not Curse of Vanishing, nor Mending: the bay never wears out. */
public class DemolitionBayItem extends BlockItem {
    public DemolitionBayItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return !enchantment.is(Enchantments.VANISHING_CURSE) && !enchantment.is(Enchantments.MENDING)
                && super.supportsEnchantment(stack, enchantment);
    }
}
