package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.MemoryTier;
import dev.micolash.jasm.autocraft.ProcessorTier;
import dev.micolash.jasm.client.AccessPortScreen;
import dev.micolash.jasm.client.ArchiveScreen;
import dev.micolash.jasm.client.DeckScreen;
import dev.micolash.jasm.client.EncodingTerminalScreen;
import dev.micolash.jasm.client.ReceivedRecipes;
import dev.micolash.jasm.client.StoragePortScreen;
import dev.micolash.jasm.client.TransferPortScreen;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
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
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * JEI support. JASM's recipes show up on their own; this adds info pages, a page of what each fuel makes in the
 * Combustion Generators, and lets JEI see the Deck's grid, its wafer settings window and the filter slots in it. JEI's
 * "+" fills a Crafting Deck's crafting grid. Chemicals and other mods' materials can be dragged into filter rows.
 */
@JeiPlugin
public class JasmJeiPlugin implements IModPlugin {
    /** What a dragged ingredient names in a filter row: an item, or the ID of a fluid or something else. */
    private record Dropped(@Nullable Item item, @Nullable Identifier id) {}

    private static <I> @Nullable Dropped dropped(ITypedIngredient<I> ingredient) {
        Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK).filter(s -> !s.isEmpty());
        if (stack.isPresent()) return new Dropped(stack.get().getItem(), null);
        Optional<FluidStack> fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK).filter(f -> !f.isEmpty());
        if (fluid.isPresent()) return new Dropped(null, BuiltInRegistries.FLUID.getKey(fluid.get().getFluid()));
        Identifier id = JeiMaterials.idOf(ingredient);
        return id == null ? null : new Dropped(null, id);
    }

    @Override
    public Identifier getPluginUid() {
        return Jasm.id("main");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new GeneratorFuelCategory(registration.getJeiHelpers().getGuiHelper()),
                new QuenchingCategory(registration.getJeiHelpers().getGuiHelper()),
                new WorkshopCategory(registration.getJeiHelpers().getGuiHelper()),
                new WorkshopRecipeCategory(registration.getJeiHelpers().getGuiHelper()),
                new FoundryCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (GeneratorTier tier : GeneratorTier.values()) {
            registration.addCraftingStation(GeneratorFuelCategory.TYPE, JasmItems.generator(tier));
        }
        registration.addCraftingStation(QuenchingCategory.TYPE, Items.WATER_BUCKET);
        registration.addCraftingStation(WorkshopCategory.TYPE, JasmItems.CHIP_WORKSHOP.get());
        registration.addCraftingStation(WorkshopRecipeCategory.TYPE, JasmItems.CHIP_WORKSHOP.get());
        registration.addCraftingStation(FoundryCategory.TYPE, JasmItems.CRYSTAL_FOUNDRY.get());
        for (ItemLike deck : craftingDecks()) {
            registration.addCraftingStation(RecipeTypes.CRAFTING, deck);
        }
    }

    private static List<ItemLike> craftingDecks() {
        return Arrays.stream(DeckTier.values()).filter(DeckTier::hasCraftingDeck).<ItemLike>map(JasmItems::craftingDeck).toList();
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new DeckTransferHandler(registration.getTransferHelper()), RecipeTypes.CRAFTING);
        registration.addRecipeTransferHandler(new TerminalTransferHandler(registration.getTransferHelper()), RecipeTypes.CRAFTING);
        registration.addUniversalRecipeTransferHandler(new ProcessingTransferHandler(registration.getTransferHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        info(registration, "capacity_wafer", 3, Arrays.stream(WaferTier.values()).filter(tier -> !tier.isTyped() && !tier.isFluid()).map(JasmItems::wafer).toList());
        info(registration, "type_wafer", 3, Arrays.stream(WaferTier.values()).filter(tier -> tier.isTyped() && !tier.isFluid()).map(JasmItems::wafer).toList());
        info(registration, "fluid_capacity_wafer", 3, Arrays.stream(WaferTier.values()).filter(tier -> !tier.isTyped() && tier.isFluid()).map(JasmItems::wafer).toList());
        info(registration, "fluid_type_wafer", 3, Arrays.stream(WaferTier.values()).filter(tier -> tier.isTyped() && tier.isFluid()).map(JasmItems::wafer).toList());
        info(registration, "deck", 3, Arrays.stream(DeckTier.values()).map(JasmItems::deck).toList());
        info(registration, "crafting_deck", 3, craftingDecks());
        info(registration, "recipe_card", 2, List.of(JasmItems.RECIPE_CARD.get(), JasmItems.FILLED_RECIPE_CARD.get()));
        info(registration, "encoding_terminal", 2, List.of(JasmItems.ENCODING_TERMINAL.get()));
        info(registration, "recipe_rack", 2, List.of(JasmItems.RECIPE_RACK.get()));
        info(registration, "data_cable", 2, JasmItems.cables());
        info(registration, "crafting_server", 2, List.of(JasmItems.CRAFTING_SERVER.get()));
        info(registration, "access_port", 2, List.of(JasmItems.ACCESS_PORT.get()));
        info(registration, "redstone_upgrade", 2, List.of(JasmItems.REDSTONE_UPGRADE.get()));
        info(registration, "processor", 1, Arrays.stream(ProcessorTier.values()).map(JasmItems::processor).toList());
        info(registration, "storage_module", 1, Arrays.stream(MemoryTier.values()).map(JasmItems::module).toList());
        info(registration, "archive", 2, Arrays.stream(ArchiveTier.values()).map(JasmItems::archive).toList());
        info(registration, "crystal_seed", 3, List.of(JasmItems.CRYSTAL_SEED.get(), JasmItems.SEEDED_AMETHYST.get(),
                JasmItems.WORN_SEEDED_AMETHYST.get(), JasmItems.CRACKED_SEEDED_AMETHYST.get()));
        info(registration, "crystal_resonator", 2, List.of(JasmItems.CRYSTAL_RESONATOR.get()));
        info(registration, "bitling", 3, JasmItems.bitlings());
        info(registration, "chip_workshop", 2, List.of(JasmItems.CHIP_WORKSHOP.get()));
        info(registration, "bitling_station", 2, List.of(JasmItems.BITLING_STATION.get()));
        info(registration, "network_brain", 2, List.of(JasmItems.NETWORK_BRAIN.get()));
        info(registration, "network_chamber", 1, List.of(JasmItems.NETWORK_CHAMBER.get()));
        info(registration, "basic_bitling", 2, List.of(JasmItems.bitling(BitlingKind.BASIC, BitlingStage.BITLING),
                JasmItems.WILD_BITLING_SPAWN_EGG.get()));
        registration.addRecipes(WorkshopCategory.TYPE, JasmItems.bitlings());
        registration.addRecipes(FoundryCategory.TYPE, List.of(FoundryCategory.Grow.fromConfig()));
        info(registration, "crystal_foundry", 2, List.of(JasmItems.CRYSTAL_FOUNDRY.get()));
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
        // The terminal's fluid marker is not a real item: keep it out of the item list.
        runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.of(new ItemStack(JasmItems.FLUID_MARKER.get())));
        IRecipeManager recipes = runtime.getRecipeManager();
        List<GeneratorFuelCategory.Fuel> fuels = recipes.createRecipeLookup(RecipeTypes.SMELTING_FUEL).get()
                .map(fuel -> new GeneratorFuelCategory.Fuel(fuel.getInputs(), fuel.getBurnTime()))
                .toList();
        recipes.addRecipes(GeneratorFuelCategory.TYPE, fuels);
        recipes.addRecipes(QuenchingCategory.TYPE, ReceivedRecipes.quenching().stream().map(QuenchingCategory.Quench::of).toList());
        recipes.addRecipes(WorkshopRecipeCategory.TYPE, ReceivedRecipes.workshop());
        JeiMaterials.start(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiMaterials.stop();
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(EncodingTerminalScreen.class, new IGhostIngredientHandler<>() {
            /** Items and fluids dragged out of JEI can be dropped onto the terminal's ghost grid (fluids only where a machine is chosen). */
            @Override
            public <I> List<Target<I>> getTargetsTyped(EncodingTerminalScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
                if (stack.isEmpty()) {
                    stack = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK).filter(fluid -> !fluid.isEmpty() && screen.getMenu().processing())
                            .map(ProcessingTransferHandler::marker);
                } else if (!stack.get().isEmpty()) {
                    stack = Optional.of(stack.get().copyWithCount(1));
                }
                if (stack.isEmpty() && screen.getMenu().processing()) {
                    stack = JeiMaterials.markerOf(ingredient);
                }
                if (stack.isEmpty() || stack.get().isEmpty()) {
                    return List.of();
                }
                ItemStack dropping = stack.get();
                List<Target<I>> targets = new ArrayList<>();
                for (int i = 0; i < screen.ghostSlots(); i++) {
                    int slot = i;
                    Rect2i area = screen.ghostSlotArea(i);
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() {
                            return area;
                        }

                        @Override
                        public void accept(I dropped) {
                            ClientPacketDistributor
                                    .sendToServer(new CraftPayloads.Ghost(screen.getMenu().containerId, slot, List.of(dropping)));
                        }
                    });
                }
                return targets;
            }

            @Override
            public void onComplete() {}
        });
        registration.addGuiContainerHandler(EncodingTerminalScreen.class, new IGuiContainerHandler<>() {
            /** Keeps JEI's item list from covering the terminal's side panels. */
            @Override
            public List<Rect2i> getGuiExtraAreas(EncodingTerminalScreen screen) {
                return screen.sidePanelAreas();
            }
        });
        registration.addGuiContainerHandler(AccessPortScreen.class, new IGuiContainerHandler<>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(AccessPortScreen screen) {
                return screen.extraAreas();
            }
        });
        registration.addGuiContainerHandler(TransferPortScreen.class, new IGuiContainerHandler<>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(TransferPortScreen screen) { return screen.extraAreas(); }
        });
        registration.addGhostIngredientHandler(TransferPortScreen.class, new IGhostIngredientHandler<>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(TransferPortScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Dropped drop = dropped(ingredient);
                if (drop == null) return List.of();
                List<Target<I>> targets = new ArrayList<>();
                List<Rect2i> areas = screen.filterSlots();
                for (int i = 0; i < areas.size(); i++) {
                    int index = i;
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() { return areas.get(index); }
                        @Override
                        public void accept(I dropped) {
                            if (drop.item() != null) screen.setFilterItem(index, drop.item());
                            else screen.setFilterMaterial(index, drop.id());
                        }
                    });
                }
                return targets;
            }
            @Override
            public void onComplete() {}
        });
        registration.addGhostIngredientHandler(StoragePortScreen.class, new IGhostIngredientHandler<>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(StoragePortScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Dropped drop = dropped(ingredient);
                if (drop == null) return List.of();
                List<Target<I>> targets = new ArrayList<>();
                List<Rect2i> areas = screen.filterSlots();
                for (int i = 0; i < areas.size(); i++) {
                    int index = i;
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() { return areas.get(index); }
                        @Override
                        public void accept(I dropped) {
                            if (drop.item() != null) screen.setFilterItem(index, drop.item());
                            else screen.setFilterMaterial(index, drop.id());
                        }
                    });
                }
                return targets;
            }
            @Override
            public void onComplete() {}
        });
        registration.addGuiContainerHandler(ArchiveScreen.class, new IGuiContainerHandler<>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(ArchiveScreen screen) {
                return screen.linkWindowArea().map(List::of).orElse(List.of());
            }
        });
        registration.addGuiContainerHandler(DeckScreen.class, new IGuiContainerHandler<>() {
            /** Keeps JEI's item list from covering the settings window where it sticks out past the Deck. */
            @Override
            public List<Rect2i> getGuiExtraAreas(DeckScreen screen) {
                return screen.windowAreas();
            }

            /** Items and fluids in the Deck grid, and items in filter slots, work with JEI's recipe and usage keys. */
            @Override
            public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory,
                    DeckScreen screen, double mouseX, double mouseY) {
                Optional<? extends IClickableIngredient<?>> item = screen.itemAt(mouseX, mouseY)
                        .flatMap(shown -> factory.createBuilder(shown.stack()).buildWithArea(shown.x(), shown.y(), 16, 16));
                if (item.isPresent()) {
                    return item;
                }
                return screen.fluidAt(mouseX, mouseY).flatMap(shown -> factory
                        .createBuilder(NeoForgeTypes.FLUID_STACK, shown.fluid().toStack(FluidType.BUCKET_VOLUME))
                        .buildWithArea(shown.x(), shown.y(), 16, 16));
            }
        });
        registration.addGhostIngredientHandler(DeckScreen.class, new IGhostIngredientHandler<>() {
            /** Items dragged out of JEI can be dropped into the open settings window's filter slots, or a rule's item slot. */
            @Override
            public <I> List<Target<I>> getTargetsTyped(DeckScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK).filter(s -> !s.isEmpty());
                Dropped drop = dropped(ingredient);
                if (drop == null) {
                    return List.of();
                }
                List<Target<I>> targets = new ArrayList<>();
                if (stack.isPresent()) {
                    screen.ruleSlotArea().ifPresent(area -> targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() {
                            return area;
                        }

                        @Override
                        public void accept(I dropped) {
                            screen.setRuleItem(stack.get());
                        }
                    }));
                }
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
                            if (drop.item() != null) screen.setFilter(index, drop.item());
                            else screen.setFilterMaterial(drop.id());
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
