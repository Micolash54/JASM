package dev.micolash.jasm.client.jei;

import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.ProcessingCard;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * JEI's "+" on any recipe, with a machine chosen at the Encoding Terminal, fills the processing grid with the
 * recipe's ingredients and the output column with what it makes, counts included (fluids in millibuckets). Nothing is
 * used up. Ingredients that are neither items nor fluids (energy, chemicals) are left out.
 */
final class ProcessingTransferHandler implements IUniversalRecipeTransferHandler<EncodingTerminalMenu> {
    private final IRecipeTransferHandlerHelper helper;

    ProcessingTransferHandler(IRecipeTransferHandlerHelper helper) {
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
    public @Nullable IRecipeTransferError transferRecipe(EncodingTerminalMenu menu, Object recipe, IRecipeSlotsView slots, Player player,
            boolean maxTransfer, boolean doTransfer) {
        if (!menu.processing()) {
            return helper.createUserErrorWithTooltip(Component.translatable("screen.jasm.terminal.choose_machine"));
        }
        List<ItemStack> inputs = items(slots, RecipeIngredientRole.INPUT);
        List<ItemStack> outputs = items(slots, RecipeIngredientRole.OUTPUT);
        if (inputs.isEmpty() || outputs.isEmpty()) {
            return helper.createUserErrorWithTooltip(Component.translatable("screen.jasm.terminal.no_items"));
        }
        if (doTransfer) {
            send(menu, inputs, outputs);
        }
        return null;
    }

    /** The items and fluids JEI is showing in the slots of one role, with their counts. A fluid is a marker counting millibuckets. */
    static List<ItemStack> items(IRecipeSlotsView slots, RecipeIngredientRole role) {
        List<ItemStack> items = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews(role)) {
            Optional<ItemStack> item = slot.getDisplayedItemStack().or(() -> slot.getItemStacks().findFirst()).filter(s -> !s.isEmpty())
                    .map(ItemStack::copy);
            if (item.isPresent()) {
                items.add(item.get());
                continue;
            }
            Optional<FluidStack> fluid = slot.getDisplayedIngredient(NeoForgeTypes.FLUID_STACK)
                    .or(() -> slot.getIngredients(NeoForgeTypes.FLUID_STACK).findFirst()).filter(f -> !f.isEmpty());
            if (fluid.isPresent()) {
                items.add(marker(fluid.get()));
                continue;
            }
            // another mod's material, if JEI has a type for it
            slot.getDisplayedIngredient().or(() -> slot.getAllIngredients().findFirst()).flatMap(JeiMaterials::markerOf).ifPresent(items::add);
        }
        return items;
    }

    /** A fluid marker for what JEI shows, with the amount as its count. */
    static ItemStack marker(FluidStack fluid) {
        return FluidMarkerItem.of(FluidResource.of(fluid)).copyWithCount(Math.clamp(fluid.getAmount(), 1, ProcessingCard.MAX_FLUID));
    }

    static void send(EncodingTerminalMenu menu, List<ItemStack> inputs, List<ItemStack> outputs) {
        ClientPacketDistributor.sendToServer(new CraftPayloads.ProcessingGhost(menu.containerId, limit(inputs), limit(outputs)));
    }

    private static List<ItemStack> limit(List<ItemStack> items) {
        return items.size() <= 64 ? items : items.subList(0, 64);
    }
}
