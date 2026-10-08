package dev.micolash.jasm.pool;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import dev.micolash.jasm.transfer.TransferPortKind;
import java.util.ArrayList;
import java.util.List;
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
import org.jspecify.annotations.Nullable;

/**
 * A port that lends the block in front of it (a chest, a tank, a cauldron) to the network's storage. A full port lends
 * every such block it touches. It does no work
 * of its own: it pays its standing power use each tick, and the network pool reads the block when a Deck, port or job
 * asks.
 */
public class StoragePortBlockEntity extends TransferPortBlockEntity {
    // one store per lent face, rebuilt only when the faces change
    private @Nullable List<PoolStore> stores;
    private StorageSettings settings = StorageSettings.DEFAULT;

    public StoragePortBlockEntity(BlockPos pos, BlockState state, Direction face) {
        super(pos, state, TransferPortKind.STORAGE, face);
    }

    private StoragePortBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.FULL_STORAGE_PORT_ENTITY.get(), pos, state, TransferPortKind.STORAGE);
    }

    public static StoragePortBlockEntity full(BlockPos pos, BlockState state) {
        return new StoragePortBlockEntity(pos, state);
    }

    public StorageSettings settings() { return settings; }

    public List<PoolStore> stores() {
        List<PoolStore> found = stores;
        if (found == null) {
            List<PoolStore> made = new ArrayList<>(6);
            for (Direction side : workFaces()) made.add(new PoolStore(this, side));
            stores = found = List.copyOf(made);
        }
        return found;
    }

    // thin port shortcuts
    public BlockPos chestPos() { return worldPosition.relative(workFaces().getFirst()); }

    public PoolStore store() { return stores().getFirst(); }

    public boolean storeActive() { return storeActive(workFaces().getFirst()); }

    public void setSettings(StorageSettings settings) {
        if (this.settings.equals(settings)) return;
        this.settings = settings;
        setChanged();
        if (level instanceof ServerLevel serverLevel) NetworkPool.touch(serverLevel, worldPosition);
    }

    public boolean storeActive(Direction side) {
        return installed() && running() && !networkBlocked() && level != null && works(side)
                && level.isLoaded(worldPosition.relative(side));
    }

    /** A crafting block or Archive is never lent, as on the thin port. */
    @Override
    protected boolean serves(Direction side) {
        if (!super.serves(side)) return false;
        var entity = level.getBlockEntity(worldPosition.relative(side));
        return !(entity instanceof MachineBlockEntity || entity instanceof ArchiveBlockEntity);
    }

    @Override
    protected void servedChanged() {
        stores = null;
        if (level instanceof ServerLevel serverLevel) NetworkPool.touch(serverLevel, worldPosition);
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
