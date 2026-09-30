package dev.micolash.jasm.deck;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The handheld reader. Carries wafers and a battery; right-click opens it. A Crafting Deck also has a 3×3 crafting
 * grid fed by its wafers.
 */
public class DeckItem extends Item implements WaferHolderItem {
    private final DeckTier tier;
    private final boolean crafting;

    public DeckItem(Item.Properties properties, DeckTier tier, boolean crafting) {
        super(properties.stacksTo(1));
        this.tier = tier;
        this.crafting = crafting;
    }

    public DeckTier tier() {
        return tier;
    }

    public boolean isCrafting() {
        return crafting;
    }

    public static boolean isCrafting(ItemStack stack) {
        return stack.getItem() instanceof DeckItem deck && deck.crafting;
    }

    /** Decks must never nest inside bundles, shulker boxes, or other container items. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    /** The battery and wafers change while the Deck is in use; only a different item should make the hand dip. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    public static DeckWafers wafers(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.DECK_WAFERS.get(), DeckWafers.EMPTY);
    }

    public static int energy(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.ENERGY.get(), 0);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null || !context.isSecondaryUseActive()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ArchiveBlockEntity archive)) {
            return InteractionResult.PASS;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            var backup = ArchiveService.backupDeck(WaferStore.get(player.level().getServer()), archive, player, context.getItemInHand());
            if (backup.result() == ArchiveService.Result.OK) {
                Notices.good(player, Component.translatable("message.jasm.archive.done.backup_deck", backup.backedUp(), backup.total()));
            } else {
                String key = backup.result() == ArchiveService.Result.NO_ACCESS ? "message.jasm.archive.deck_only" : backup.result().messageKey();
                Notices.bad(player, Component.translatable(key));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the Deck. Its wafers are activated first, so copies elsewhere stop working. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND);
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the Deck in this inventory slot. */
    public static void open(ServerPlayer player, int slot) {
        ItemStack deck = player.getInventory().getItem(slot);
        if (!(deck.getItem() instanceof DeckItem)) {
            return;
        }
        DeckStorage.activate(WaferStore.get(player.level().getServer()), deck, player);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new DeckMenu(id, inventory, slot), deck.getHoverName()),
                buf -> buf.writeVarInt(slot));
    }

    @Override
    public void forEachWafer(ItemStack holder, Consumer<ItemStack> action) {
        wafers(holder).forEach(action);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.deck.wafers", wafers(stack).count(), tier.slots())
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.jasm.deck.energy", String.format("%,d", energy(stack)),
                String.format("%,d", tier.battery())).withStyle(ChatFormatting.GRAY));
    }
}
