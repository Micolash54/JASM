package dev.micolash.jasm.compat.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.client.DeckScreen;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * JEI support. JASM's recipes show up on their own; this adds info pages, a page of what each fuel makes in the
 * Combustion Generators, and lets JEI see the Deck's grid, its wafer settings window and the filter slots in it.
 */
@JeiPlugin
public class JasmJeiPlugin implements IModPlugin {
    @Override
    public Identifier getPluginUid() {
        return Jasm.id("main");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new GeneratorFuelCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (GeneratorTier tier : GeneratorTier.values()) {
            registration.addCraftingStation(GeneratorFuelCategory.TYPE, JasmItems.generator(tier));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        info(registration, "capacity_wafer", 3, Arrays.stream(WaferTier.values()).filter(tier -> !tier.isTyped()).map(JasmItems::wafer).toList());
        info(registration, "type_wafer", 3, Arrays.stream(WaferTier.values()).filter(WaferTier::isTyped).map(JasmItems::wafer).toList());
        info(registration, "deck", 3, Arrays.stream(DeckTier.values()).map(JasmItems::deck).toList());
        info(registration, "archive", 2, Arrays.stream(ArchiveTier.values()).map(JasmItems::archive).toList());
        info(registration, "combustion_generator", 2, Arrays.stream(GeneratorTier.values()).map(JasmItems::generator).toList());
    }

    /** An info page of {@code paragraphs} paragraphs, shared by every tier of an item. */
    private static void info(IRecipeRegistration registration, String page, int paragraphs, List<? extends ItemLike> items) {
        Component[] text = new Component[paragraphs * 2 - 1];
        for (int i = 0; i < paragraphs; i++) {
            text[i * 2] = Component.translatable("jei.jasm.info." + page + "." + (i + 1));
            if (i > 0) {
                text[i * 2 - 1] = Component.empty();
            }
        }
        registration.addItemStackInfo(items.stream().map(ItemStack::new).toList(), text);
    }

    /** Fuel burn times are only known once JEI has worked out its own furnace fuel list, so the pages are added then. */
    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        IRecipeManager recipes = runtime.getRecipeManager();
        List<GeneratorFuelCategory.Fuel> fuels = recipes.createRecipeLookup(RecipeTypes.SMELTING_FUEL).get()
                .map(fuel -> new GeneratorFuelCategory.Fuel(fuel.getInputs(), fuel.getBurnTime()))
                .toList();
        recipes.addRecipes(GeneratorFuelCategory.TYPE, fuels);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(DeckScreen.class, new IGuiContainerHandler<>() {
            /** Keeps JEI's item list from covering the settings window where it sticks out past the Deck. */
            @Override
            public List<Rect2i> getGuiExtraAreas(DeckScreen screen) {
                return screen.settingsWindowArea().map(List::of).orElse(List.of());
            }

            /** Items in the Deck grid and in filter slots work with JEI's recipe and usage keys. */
            @Override
            public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory,
                    DeckScreen screen, double mouseX, double mouseY) {
                return screen.itemAt(mouseX, mouseY).flatMap(shown -> factory.createBuilder(shown.stack()).buildWithArea(shown.x(), shown.y(), 16, 16));
            }
        });
        registration.addGhostIngredientHandler(DeckScreen.class, new IGhostIngredientHandler<>() {
            /** Items dragged out of JEI can be dropped into the open settings window's filter slots. */
            @Override
            public <I> List<Target<I>> getTargetsTyped(DeckScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
                if (stack.isEmpty() || stack.get().isEmpty()) {
                    return List.of();
                }
                List<Target<I>> targets = new ArrayList<>();
                List<Rect2i> areas = screen.filterSlotAreas();
                for (int i = 0; i < areas.size(); i++) {
                    int index = i;
                    Rect2i area = areas.get(i);
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() {
                            return area;
                        }

                        @Override
                        public void accept(I dropped) {
                            screen.setFilter(index, stack.get().getItem());
                        }
                    });
                }
                return targets;
            }

            @Override
            public void onComplete() {}
        });
    }
}
