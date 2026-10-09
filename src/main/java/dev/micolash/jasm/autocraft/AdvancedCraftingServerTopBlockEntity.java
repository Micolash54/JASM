package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * The top half of an Advanced Crafting Server, so cables and neighbouring machines join it like the bottom half. It holds
 * nothing and uses no power of its own: its power, whose it is and whether it runs are the server's below it.
 */
public class AdvancedCraftingServerTopBlockEntity extends MachineBlockEntity {
    public AdvancedCraftingServerTopBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ADVANCED_CRAFTING_SERVER_TOP_ENTITY.get(), pos, state, 0);
    }

    /** The server below, if it is there. */
    public @Nullable AdvancedCraftingServerBlockEntity server() {
        return level != null && level.getBlockEntity(worldPosition.below()) instanceof AdvancedCraftingServerBlockEntity server ? server : null;
    }

    /** Shares the server's power, so a cable on the top half charges it like one on the bottom. */
    @Override
    public SimpleEnergyHandler energy() {
        AdvancedCraftingServerBlockEntity server = server();
        return server != null ? server.energy() : super.energy();
    }

    @Override
    public int drainPerTick() {
        return 0;
    }

    /** One server, one place on the network. */
    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    public boolean running() {
        AdvancedCraftingServerBlockEntity server = server();
        return server != null && server.running();
    }

    @Override
    public @Nullable UUID owner() {
        AdvancedCraftingServerBlockEntity server = server();
        return server == null ? super.owner() : server.owner();
    }

    @Override
    public String ownerName() {
        AdvancedCraftingServerBlockEntity server = server();
        return server == null ? super.ownerName() : server.ownerName();
    }

    @Override
    public boolean isOwner(Player player) {
        AdvancedCraftingServerBlockEntity server = server();
        return server == null ? super.isOwner(player) : server.isOwner(player);
    }

    @Override
    public void adoptOwner(UUID id, String name) {
        AdvancedCraftingServerBlockEntity server = server();
        if (server == null) {
            super.adoptOwner(id, name);
        } else {
            server.adoptOwner(id, name);
        }
    }

    @Override
    public boolean networkBlocked() {
        AdvancedCraftingServerBlockEntity server = server();
        return server == null ? super.networkBlocked() : server.networkBlocked();
    }

    @Override
    public void setNetworkBlocked(boolean blocked) {
        AdvancedCraftingServerBlockEntity server = server();
        if (server == null) {
            super.setNetworkBlocked(blocked);
        } else {
            server.setNetworkBlocked(blocked);
        }
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return NonNullList.create();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {}

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return JasmBlocks.CRAFTING_SERVER.get().getName();
    }

    /** The block opens the server below instead; this is only reached if that is gone. */
    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        AdvancedCraftingServerBlockEntity server = server();
        return server == null ? null : server.createMenu(containerId, inventory);
    }
}
