package dev.micolash.jasm.pool;

import dev.micolash.jasm.core.MaterialKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.resource.RegisteredResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/** The container kinds other mods add through NeoForge's shared handlers, apart from items and fluids. */
public final class MaterialKinds {
    // mods make their capabilities while loading and never drop them, so each list is built once and kept
    private static volatile @Nullable List<BlockCapability<ResourceHandler<Resource>, @Nullable Direction>> blocks;
    private static volatile @Nullable List<ItemCapability<ResourceHandler<Resource>, ItemAccess>> items;

    private MaterialKinds() {}

    @SuppressWarnings("unchecked")
    public static List<BlockCapability<ResourceHandler<Resource>, @Nullable Direction>> blocks() {
        var known = blocks;
        if (known == null) {
            List<BlockCapability<ResourceHandler<Resource>, @Nullable Direction>> found = new ArrayList<>();
            // getAll() is synchronized and copies, so never per tick
            for (BlockCapability<?, ?> kind : BlockCapability.getAll()) {
                if (kind.typeClass() == ResourceHandler.class && kind.contextClass() == Direction.class
                        && kind != Capabilities.Item.BLOCK && kind != Capabilities.Fluid.BLOCK)
                    found.add((BlockCapability<ResourceHandler<Resource>, @Nullable Direction>) kind);
            }
            known = blocks = List.copyOf(found);
        }
        return known;
    }

    @SuppressWarnings("unchecked")
    public static List<ItemCapability<ResourceHandler<Resource>, ItemAccess>> items() {
        var known = items;
        if (known == null) {
            List<ItemCapability<ResourceHandler<Resource>, ItemAccess>> found = new ArrayList<>();
            for (ItemCapability<?, ?> kind : ItemCapability.getAll()) {
                if (kind.typeClass() == ResourceHandler.class && kind.contextClass() == ItemAccess.class
                        && kind != Capabilities.Item.ITEM && kind != Capabilities.Fluid.ITEM)
                    found.add((ItemCapability<ResourceHandler<Resource>, ItemAccess>) kind);
            }
            known = items = List.copyOf(found);
        }
        return known;
    }

    public record Held(MaterialKey key, Holder<?> holder) {}

    /** The first modded material the stack holds (hydrogen in a filled tank), or null. */
    public static @Nullable Held held(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ItemAccess access = ItemAccess.forStack(stack.copyWithCount(1));
        for (var kind : items()) {
            ResourceHandler<Resource> handler = access.getCapability(kind);
            if (handler == null) continue;
            for (int slot = 0; slot < handler.size(); slot++) {
                Resource resource = handler.getResource(slot);
                MaterialKey key = MaterialKey.of(resource);
                if (key != null && handler.getAmountAsLong(slot) > 0)
                    return new Held(key, ((RegisteredResource<?>) resource).typeHolder());
            }
        }
        return null;
    }
}
