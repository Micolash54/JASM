package dev.micolash.jasm.pool;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import dev.micolash.jasm.transfer.TransferPortKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A port that lends the block in front of it (a chest, a tank, a cauldron) to the network's storage. It does no work
 * of its own: it pays its standing power use each tick, and the network pool reads the block when a Deck, port or job
 * asks.
 */
public class StoragePortBlockEntity extends TransferPortBlockEntity {
    private final Direction face;
    private final PoolStore store = new PoolStore(this);
    private StorageSettings settings = StorageSettings.DEFAULT;

    public StoragePortBlockEntity(BlockPos pos, BlockState state, Direction face) {
        super(pos, state, TransferPortKind.STORAGE, face);
        this.face = face;
    }

    public Direction face() { return face; }
    public BlockPos chestPos() { return worldPosition.relative(face); }
    public StorageSettings settings() { return settings; }
    public PoolStore store() { return store; }

    public void setSettings(StorageSettings settings) {
        if (this.settings.equals(settings)) return;
        this.settings = settings;
        setChanged();
        if (level instanceof ServerLevel serverLevel) NetworkPool.touch(serverLevel, worldPosition);
    }

    /** Whether the pool may use this port's block right now. */
    public boolean storeActive() {
        return installed() && running() && !networkBlocked() && level != null && level.isLoaded(chestPos());
    }

    @Override
    public void tickTransfer() {
        payForTick();
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new StoragePortMenu(id, inventory, this);
    }

    @Override
    public void writeOpening(RegistryFriendlyByteBuf buf) {
        StorageSettings.STREAM_CODEC.encode(buf, settings);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("storage_settings", StorageSettings.CODEC, settings);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settings = input.read("storage_settings", StorageSettings.CODEC).orElse(StorageSettings.DEFAULT);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(JasmComponents.STORAGE_SETTINGS.get(), settings);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        settings = components.getOrDefault(JasmComponents.STORAGE_SETTINGS.get(), StorageSettings.DEFAULT);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("storage_settings");
    }
}
