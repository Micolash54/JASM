package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.client.DeckScreen;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.GridVariants;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * JEI's "+" on a crafting recipe fills a Crafting Deck's grid. The screen only says which items each slot may take;
 * the server picks them from the Deck's wafers and the inventory, taking an item with extra data when the recipe wants
 * it plain and no plain copy is there, and checks everything again. When some ingredients
 * are missing, the rest still go in. Missing ones the network can craft light up blue, the others red, and the "+"
 * turns blue, or orange while anything red is left. Ctrl-click also asks for the blue ones, one request window after
 * another. Only when nothing at all can be had does the "+" refuse.
 */
final class DeckTransferHandler implements IRecipeTransferHandler<DeckMenu, RecipeHolder<CraftingRecipe>> {
    /** The red JEI itself uses for slots that can't be filled. */
    private static final int MISSING_SLOT = 0x66FF0000;
    /** Slots the network can craft instead. */
    private static final int CRAFTABLE_SLOT = 0x400000FF;
    /** The "+" while some of the recipe is missing, and while all of what's missing can be crafted. */
    private static final int SOME_MISSING = 0x80FFA500;
    private static final int ALL_CRAFTABLE = 0x804545FF;

    private final IRecipeTransferHandlerHelper helper;

    DeckTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override
    public Class<? extends DeckMenu> getContainerClass() {
        return DeckMenu.class;
    }

    @Override
    public Optional<MenuType<DeckMenu>> getMenuType() {
        return Optional.of(JasmMenus.DECK.get());
    }

    @Override
    public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    /** JEI still routes its newer call through this one. */
    @Override
    @SuppressWarnings("removal")
    public @Nullable IRecipeTransferError transferRecipe(DeckMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
            Player player, boolean maxTransfer, boolean doTransfer) {
        if (!menu.isCrafting()) {
            return helper.createInternalError();
        }
        List<IRecipeSlotView> inputs = slots.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.size() > 9) {
            return helper.createInternalError();
        }
        Map<ItemResource, Long> available = new HashMap<>(menu.view().contents());
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && i != menu.deckSlot()) {
                available.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        List<List<ItemResource>> wanted = new ArrayList<>();
        List<IRecipeSlotView> missing = new ArrayList<>();
        List<IRecipeSlotView> craftable = new ArrayList<>();
        // What to craft for the craftable slots, one per slot, in the recipe's order.
        Map<ItemResource, Integer> toCraft = new LinkedHashMap<>();
        int needed = 0;
        for (IRecipeSlotView input : inputs) {
            if (!input.isEmpty()) {
                needed++;
            }
            List<ItemResource> options = input.getItemStacks().filter(DeckMenu::allowedInGrid).map(ItemResource::of).distinct()
                    .limit(DeckPayloads.MAX_OPTIONS).toList();
            wanted.add(options);
            if (options.isEmpty()) {
                if (!input.isEmpty()) {
                    missing.add(input);
                }
                continue;
            }
            ItemResource found = options.stream().filter(o -> available.getOrDefault(o, 0L) > 0).findFirst().orElse(null);
            if (found == null) {
                found = GridVariants.best(options, available);
            }
            if (found == null) {
                ItemResource craft = options.stream().filter(menu.view().craftable()::contains).findFirst().orElse(null);
                if (craft == null) {
                    missing.add(input);
                } else {
                    craftable.add(input);
                    toCraft.merge(craft, 1, Integer::sum);
                }
            } else {
                available.merge(found, -1L, Long::sum);
            }
        }
        if (needed > 0 && missing.size() == needed) {
            return helper.createUserErrorForMissingSlots(Component.translatable("jei.jasm.transfer.missing"), missing);
        }
        if (doTransfer) {
            ClientPacketDistributor.sendToServer(new DeckPayloads.FillGrid(menu.containerId, wanted, maxTransfer));
            if (!toCraft.isEmpty() && Minecraft.getInstance().hasControlDown()) {
                DeckScreen.queueCrafts(menu.containerId, toCraft.entrySet().stream()
                        .map(e -> new DeckScreen.QueuedCraft(e.getKey(), e.getValue())).toList());
            }
        }
        return missing.isEmpty() && craftable.isEmpty() ? null : new SomeMissing(missing, craftable);
    }

    /** Some of the recipe is missing: the "+" still works, and shows which slots stay empty and which can be crafted. */
    private record SomeMissing(List<IRecipeSlotView> missing, List<IRecipeSlotView> craftable) implements IRecipeTransferError {
        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return missing.isEmpty() ? ALL_CRAFTABLE : SOME_MISSING;
        }

        @Override
        public void showError(GuiGraphicsExtractor graphics, int mouseX, int mouseY, IRecipeSlotsView slots, int recipeX, int recipeY) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(recipeX, recipeY);
            for (IRecipeSlotView slot : missing) {
                slot.drawHighlight(graphics, MISSING_SLOT);
            }
            for (IRecipeSlotView slot : craftable) {
                slot.drawHighlight(graphics, CRAFTABLE_SLOT);
            }
            graphics.pose().popMatrix();
        }

        /** Only while the "+" is blue: everything missing can be crafted. */
        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            if (missing.isEmpty()) {
                tooltip.add(Component.translatable("jei.jasm.transfer.craft_missing"));
            }
        }
    }
}
