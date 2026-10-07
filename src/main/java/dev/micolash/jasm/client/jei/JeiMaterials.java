package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.autocraft.MaterialMarkerItem;
import dev.micolash.jasm.client.MaterialIcons;
import dev.micolash.jasm.core.MaterialKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** JEI's icons for other mods' materials, looked up by ID. Lives as long as JEI's runtime does. */
final class JeiMaterials implements MaterialIcons.Source {
    private record Shown<V>(IIngredientType<V> type, V ingredient) {}

    private static @Nullable JeiMaterials current;

    private final IIngredientManager manager;
    private Map<Identifier, Shown<?>> byId = Map.of();
    private boolean stale = true;

    private JeiMaterials(IIngredientManager manager) {
        this.manager = manager;
    }

    static void start(IJeiRuntime runtime) {
        JeiMaterials materials = new JeiMaterials(runtime.getIngredientManager());
        // JEI has no way to unregister, a listener left on an old runtime only marks a dead map stale
        runtime.getIngredientManager().registerIngredientListener(new IIngredientManager.IIngredientListener() {
            @Override
            public <V> void onIngredientsAdded(IIngredientHelper<V> helper, Collection<ITypedIngredient<V>> ingredients) {
                materials.stale = true;
            }

            @Override
            public <V> void onIngredientsRemoved(IIngredientHelper<V> helper, Collection<ITypedIngredient<V>> ingredients) {
                materials.stale = true;
            }
        });
        current = materials;
        MaterialIcons.setSource(materials);
    }

    static void stop() {
        current = null;
        MaterialIcons.setSource(null);
    }

    private Map<Identifier, Shown<?>> byId() {
        if (stale) {
            stale = false;
            Map<Identifier, Shown<?>> found = new HashMap<>();
            for (IIngredientType<?> type : manager.getRegisteredIngredientTypes()) {
                if (type != VanillaTypes.ITEM_STACK && type != NeoForgeTypes.FLUID_STACK) add(found, type);
            }
            byId = Map.copyOf(found);
        }
        return byId;
    }

    // the first type to claim an ID keeps it
    private <V> void add(Map<Identifier, Shown<?>> found, IIngredientType<V> type) {
        IIngredientHelper<V> helper = manager.getIngredientHelper(type);
        for (V ingredient : manager.getAllIngredients(type)) {
            found.putIfAbsent(helper.getIdentifier(ingredient), new Shown<>(type, ingredient));
        }
    }

    @Override
    public boolean draw(GuiGraphicsExtractor graphics, MaterialKey key, int x, int y) {
        Shown<?> shown = byId().get(key.id());
        if (shown == null) return false;
        render(graphics, shown, x, y);
        return true;
    }

    private <V> void render(GuiGraphicsExtractor graphics, Shown<V> shown, int x, int y) {
        manager.getIngredientRenderer(shown.type()).render(graphics, shown.ingredient(), x, y);
    }

    /** A marker for a dragged or shown material, a thousand units of it. Empty for items, fluids and what no registry knows. */
    static Optional<ItemStack> markerOf(ITypedIngredient<?> ingredient) {
        if (ingredient.getType() == VanillaTypes.ITEM_STACK || ingredient.getType() == NeoForgeTypes.FLUID_STACK) return Optional.empty();
        Identifier id = idOf(ingredient);
        MaterialKey key = id == null ? null : MaterialKey.byId(id);
        return key == null ? Optional.empty() : Optional.of(MaterialMarkerItem.of(key).copyWithCount(MATERIAL_DEFAULT));
    }

    // JEI doesn't say how much a recipe uses of a material, so a card starts at a bucket's worth and the player edits it
    private static final int MATERIAL_DEFAULT = 1000;

    /** The ID of a dragged ingredient, for filter rows. Null without a JEI runtime. */
    static <V> @Nullable Identifier idOf(ITypedIngredient<V> ingredient) {
        JeiMaterials materials = current;
        return materials == null ? null : materials.manager.getIngredientHelper(ingredient.getType()).getIdentifier(ingredient.getIngredient());
    }
}
