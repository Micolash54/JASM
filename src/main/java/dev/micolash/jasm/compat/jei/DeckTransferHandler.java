package dev.micolash.jasm.compat.jei;

import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.types.IRecipeType;
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
 * the server picks them from the Deck's wafers and the inventory, and checks everything again.
 */
final class DeckTransferHandler implements IRecipeTransferHandler<DeckMenu, RecipeHolder<CraftingRecipe>> {
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
        for (IRecipeSlotView input : inputs) {
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
                missing.add(input);
            } else {
                available.merge(found, -1L, Long::sum);
            }
        }
        if (!missing.isEmpty()) {
            return helper.createUserErrorForMissingSlots(Component.translatable("jei.jasm.transfer.missing"), missing);
        }
        if (doTransfer) {
            ClientPacketDistributor.sendToServer(new DeckPayloads.FillGrid(menu.containerId, wanted, maxTransfer));
        }
        return null;
    }
}
