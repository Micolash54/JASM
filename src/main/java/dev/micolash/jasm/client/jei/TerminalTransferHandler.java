package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/** JEI's "+" on a crafting recipe fills the Encoding Terminal's ghost grid with the items JEI is showing. Nothing is used up. */
final class TerminalTransferHandler implements IRecipeTransferHandler<EncodingTerminalMenu, RecipeHolder<CraftingRecipe>> {
    private final IRecipeTransferHandlerHelper helper;

    TerminalTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override
    public Class<? extends EncodingTerminalMenu> getContainerClass() {
        return EncodingTerminalMenu.class;
    }

    @Override
    public Optional<MenuType<EncodingTerminalMenu>> getMenuType() {
        return Optional.of(JasmMenus.ENCODING_TERMINAL.get());
    }

    @Override
    public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    /** JEI still routes its newer call through this one. */
    @Override
    @SuppressWarnings("removal")
    public @Nullable IRecipeTransferError transferRecipe(EncodingTerminalMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
            Player player, boolean maxTransfer, boolean doTransfer) {
        if (menu.processing()) {
            // With a machine chosen, a crafting recipe fills the processing grid like any other.
            if (doTransfer) {
                ProcessingTransferHandler.send(menu, ProcessingTransferHandler.items(slots, RecipeIngredientRole.INPUT),
                        ProcessingTransferHandler.items(slots, RecipeIngredientRole.OUTPUT));
            }
            return null;
        }
        List<IRecipeSlotView> inputs = slots.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.size() > 9) {
            return helper.createInternalError();
        }
        if (doTransfer) {
            List<ItemStack> grid = new ArrayList<>();
            for (IRecipeSlotView input : inputs) {
                grid.add(input.getDisplayedItemStack().or(() -> input.getItemStacks().findFirst()).map(s -> s.copyWithCount(1)).orElse(ItemStack.EMPTY));
            }
            ClientPacketDistributor.sendToServer(new CraftPayloads.Ghost(menu.containerId, -1, grid));
        }
        return null;
    }
}
