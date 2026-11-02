package dev.micolash.jasm.relay;

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
 * The upper half of a Relay Chatterbox, so cables and neighbouring machines join it like the lower half. It holds
 * nothing and is no end of a pair: its power and whose it is are the lower half's.
 */
public class RelayChatterboxTopBlockEntity extends MachineBlockEntity {
    public RelayChatterboxTopBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.RELAY_CHATTERBOX_TOP_ENTITY.get(), pos, state, 0);
    }

    /** The lower half, if it is there. */
    public @Nullable RelayChatterboxBlockEntity lower() {
        return level != null && level.getBlockEntity(worldPosition.below()) instanceof RelayChatterboxBlockEntity relay ? relay : null;
    }

    @Override
    public SimpleEnergyHandler energy() {
        RelayChatterboxBlockEntity lower = lower();
        return lower != null ? lower.energy() : super.energy();
    }

    @Override
    public int drainPerTick() {
        return 0;
    }

    /** One Chatterbox, one place on the network. */
    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    public boolean running() {
        RelayChatterboxBlockEntity lower = lower();
        return lower != null && lower.running();
    }

    @Override
    public @Nullable UUID owner() {
        RelayChatterboxBlockEntity lower = lower();
        return lower == null ? super.owner() : lower.owner();
    }

    @Override
    public String ownerName() {
        RelayChatterboxBlockEntity lower = lower();
        return lower == null ? super.ownerName() : lower.ownerName();
    }

    @Override
    public boolean isOwner(Player player) {
        RelayChatterboxBlockEntity lower = lower();
        return lower == null ? super.isOwner(player) : lower.isOwner(player);
    }

    @Override
    public void adoptOwner(UUID id, String name) {
        RelayChatterboxBlockEntity lower = lower();
        if (lower == null) {
            super.adoptOwner(id, name);
        } else {
            lower.adoptOwner(id, name);
        }
    }

    @Override
    public boolean networkBlocked() {
        RelayChatterboxBlockEntity lower = lower();
        return lower == null ? super.networkBlocked() : lower.networkBlocked();
    }

    @Override
    public void setNetworkBlocked(boolean blocked) {
        RelayChatterboxBlockEntity lower = lower();
        if (lower == null) {
            super.setNetworkBlocked(blocked);
        } else {
            lower.setNetworkBlocked(blocked);
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
        return JasmBlocks.RELAY_CHATTERBOX.get().getName();
    }

    /** The block opens the lower half instead; this is only reached if that is gone. */
    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
    }
}
