package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Stands for one of another mod's materials where the Encoding Terminal keeps an example item. Never given to a player. */
public class MaterialMarkerItem extends Item {
    public MaterialMarkerItem(Item.Properties properties) {
        super(properties);
    }

    public static ItemStack of(MaterialKey key) {
        ItemStack stack = new ItemStack(JasmItems.MATERIAL_MARKER.get());
        stack.set(JasmComponents.MATERIAL_MARKER.get(), key);
        return stack;
    }

    /** The material a stack stands for, or null if it is not a marker. */
    public static @Nullable MaterialKey materialOf(ItemStack stack) {
        return stack.is(JasmItems.MATERIAL_MARKER.get()) ? stack.get(JasmComponents.MATERIAL_MARKER.get()) : null;
    }

    public static boolean isMarker(ItemStack stack) {
        return materialOf(stack) != null;
    }

    @Override
    public Component getName(ItemStack stack) {
        MaterialKey key = materialOf(stack);
        // the translation if some mod gave one, else the id read plainly (the client has nicer names, the server has this)
        return key == null ? super.getName(stack) : Component.translatableWithFallback(key.translationKey(),
                GridEntries.readableName(key.id().getPath()));
    }
}
