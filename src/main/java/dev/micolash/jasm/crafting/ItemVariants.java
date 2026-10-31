package dev.micolash.jasm.crafting;

import dev.micolash.jasm.Jasm;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * When a stack may be used in place of another stack of the same item that carries other data, like seeds with another
 * mod's crop stats for plain seeds. One rule for crafting cards, processing cards and the Crafting Deck's grid fill.
 *
 * <p>Allowed: the same item, stacking to more than one, and neither stack carrying a protected mark. Protected marks are
 * the ones a player would miss if a craft used the stack up: enchantments (stored ones too), a custom name, damage,
 * container or potion contents, and anything JASM keeps on an item. Any other data passes. Whoever asks still checks
 * that the recipe takes the stack.
 */
public final class ItemVariants {
    private ItemVariants() {}

    /** Marks that keep a stack out of every craft it wasn't picked for. */
    private static final Set<DataComponentType<?>> PROTECTED = Set.of(
            DataComponents.ENCHANTMENTS,
            DataComponents.STORED_ENCHANTMENTS,
            DataComponents.CUSTOM_NAME,
            DataComponents.DAMAGE,
            DataComponents.CONTAINER,
            DataComponents.BUNDLE_CONTENTS,
            DataComponents.CHARGED_PROJECTILES,
            DataComponents.POTION_CONTENTS);

    /** Whether {@code candidate} may stand in for {@code wanted}. Two equal stacks are left to the caller. */
    public static boolean standsIn(ItemStack wanted, ItemStack candidate) {
        return !wanted.isEmpty() && !candidate.isEmpty() && candidate.is(wanted.getItem())
                && wanted.getMaxStackSize() > 1 && candidate.getMaxStackSize() > 1
                && !isProtected(wanted) && !isProtected(candidate);
    }

    public static boolean standsIn(ItemResource wanted, ItemResource candidate) {
        return !wanted.isEmpty() && !candidate.isEmpty() && candidate.getItem() == wanted.getItem()
                && standsIn(wanted.toStack(1), candidate.toStack(1));
    }

    /** Whether the stack carries a mark that keeps it out of crafts it wasn't picked for. */
    public static boolean isProtected(ItemStack stack) {
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            if (PROTECTED.contains(type)) {
                return true;
            }
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (id != null && Jasm.MODID.equals(id.getNamespace())) {
                return true;
            }
        }
        return false;
    }
}
