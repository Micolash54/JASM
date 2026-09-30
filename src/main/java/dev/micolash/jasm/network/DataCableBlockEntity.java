package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.transfer.TransferPortKind;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import dev.micolash.jasm.registry.JasmItems;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import org.jspecify.annotations.Nullable;

/** The independent Access Ports mounted on a cable's faces. */
public class DataCableBlockEntity extends BlockEntity {
    private final Map<Direction, AccessPortBlockEntity> ports = new EnumMap<>(Direction.class);

    public DataCableBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.DATA_CABLE_ENTITY.get(), pos, state);
    }

    public List<AccessPortBlockEntity> ports() {
        return List.copyOf(ports.values());
    }

    public @Nullable AccessPortBlockEntity port(Direction side) {
        return ports.get(side);
    }

    /** The mounted part, rather than the cable, supplies this face's item input. */
    public @Nullable ResourceHandler<ItemResource> itemInput(@Nullable Direction side) {
        var port = side == null ? null : ports.get(side);
        return port instanceof AttachedPort access ? access.itemInput : null;
    }

    public boolean attach(Direction side, ItemStack stack, Player player) {
        if (level == null || (!stack.is(JasmItems.THIN_ACCESS_PORT.get()) && TransferPortKind.of(stack) == null) || ports.containsKey(side)) return false;
        BlockPos next = worldPosition.relative(side);
        if (level.getBlockState(next).getBlock() instanceof DataCableBlock
                || level.getBlockEntity(next) instanceof MachineBlockEntity
                || level.getBlockEntity(next) instanceof ArchiveBlockEntity
                || level.getBlockEntity(next) instanceof NetworkPowerSource) return false;
        if (level instanceof ServerLevel serverLevel) {
            Networks.at(serverLevel, worldPosition);
            UUID owner = CableClaims.get(serverLevel).owner(serverLevel, worldPosition);
            if (owner != null && !owner.equals(player.getUUID())
                    && !MachineAccess.trustedBy(Networks.at(serverLevel, worldPosition), owner, player.getUUID())) return false;
        }
        AccessPortBlockEntity port = createPort(side, TransferPortKind.of(stack));
        port.setLevel(level);
        port.applyComponentsFromItemStack(stack);
        port.setOwner(player);
        ports.put(side, port);
        if (level instanceof ServerLevel serverLevel) {
            CableClaims claims = CableClaims.get(serverLevel);
            if (claims.owner(serverLevel, worldPosition) == null && !claims.blocked(serverLevel, worldPosition)) {
                claims.set(serverLevel, worldPosition, player.getUUID(), false);
            }
            syncOwners();
            Networks.invalidate(serverLevel);
        }
        changed();
        return true;
    }

    public boolean detach(Direction side, boolean drop) {
        AccessPortBlockEntity port = ports.remove(side);
        if (port == null) return false;
        if (level instanceof ServerLevel) {
            if (drop) Block.popResource(level, worldPosition, portItem(port));
            Containers.dropContents(level, worldPosition, port);
            port.onChunkUnloaded();
            port.setRemoved();
        }
        if (level instanceof ServerLevel serverLevel) Networks.invalidate(serverLevel);
        changed();
        return true;
    }

    public ItemStack portItem(AccessPortBlockEntity port) {
        ItemStack item = new ItemStack(port instanceof TransferPortBlockEntity transfer ? transfer.kind().item() : JasmItems.THIN_ACCESS_PORT.get());
        item.applyComponents(port.collectComponents());
        return item;
    }

    public void syncOwners() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        CableClaims claims = CableClaims.get(serverLevel);
        UUID owner = claims.owner(serverLevel, worldPosition);
        for (AccessPortBlockEntity port : ports.values()) {
            if (owner != null) port.adoptOwner(owner, claims.ownerName(owner));
            port.setNetworkBlocked(claims.blocked(serverLevel, worldPosition));
        }
    }

    public void changed() {
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            if (getBlockState().getBlock() instanceof DataCableBlock cable) cable.refreshConnections(serverLevel, worldPosition);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            level.invalidateCapabilities(worldPosition);
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DataCableBlockEntity cable) {
        if (state.getValue(DataCableBlock.HAS_PORTS) != !cable.ports.isEmpty() && state.getBlock() instanceof DataCableBlock block
                && level instanceof ServerLevel serverLevel) block.refreshConnections(serverLevel, pos);
        if (cable.ports.isEmpty()) return;
        cable.syncOwners();
        for (AccessPortBlockEntity port : cable.ports.values()) {
            if (port instanceof TransferPortBlockEntity transfer) transfer.tickTransfer();
            else AccessPortBlockEntity.serverTick(level, pos, port.getBlockState(), port);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        ports.values().forEach(port -> port.setLevel(level));
    }

    @Override
    public void onChunkUnloaded() {
        ports.values().forEach(AccessPortBlockEntity::onChunkUnloaded);
        super.onChunkUnloaded();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel) {
            for (Direction side : List.copyOf(ports.keySet())) detach(side, true);
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        var saved = output.childrenList("ports");
        ports.forEach((side, port) -> {
            ValueOutput child = saved.addChild();
            child.store("side", Direction.CODEC, side);
            if (port instanceof TransferPortBlockEntity transfer) child.putString("kind", transfer.kind().name());
            port.saveCustomOnly(child.child("port"));
        });
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ports.values().forEach(BlockEntity::setRemoved);
        ports.clear();
        for (ValueInput child : input.childrenListOrEmpty("ports")) {
            child.read("side", Direction.CODEC).ifPresent(side -> {
                String kind = child.getStringOr("kind", "");
                TransferPortKind parsed = null;
                try { parsed = TransferPortKind.valueOf(kind); } catch (IllegalArgumentException ignored) {}
                AccessPortBlockEntity port = createPort(side, parsed);
                port.loadCustomOnly(child.childOrEmpty("port"));
                if (level != null) port.setLevel(level);
                ports.put(side, port);
            });
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private AccessPortBlockEntity createPort(Direction side, @Nullable TransferPortKind kind) {
        return kind == null ? new AttachedPort(side) : new AttachedTransferPort(side, kind);
    }

    private final class AttachedTransferPort extends TransferPortBlockEntity {
        private final Direction face;
        AttachedTransferPort(Direction face, TransferPortKind kind) {
            super(DataCableBlockEntity.this.worldPosition, JasmBlocks.ACCESS_PORT.get().defaultBlockState(), kind, face);
            this.face = face;
        }
        @Override public void setChanged() { DataCableBlockEntity.this.setChanged(); }
        @Override public boolean isRemoved() { return super.isRemoved() || DataCableBlockEntity.this.isRemoved() || ports.get(face) != this; }
        @Override public boolean installed() { return !isRemoved(); }
    }

    private final class AttachedPort extends AccessPortBlockEntity {
        private final Direction face;
        private final ResourceHandler<ItemResource> itemInput;
        AttachedPort(Direction face) {
            super(DataCableBlockEntity.this.worldPosition, JasmBlocks.ACCESS_PORT.get().defaultBlockState());
            this.face = face;
            this.itemInput = new WorldlyContainerWrapper(this, face);
        }
        @Override public boolean hasMachine(Direction side) { return side == face && super.hasMachine(side); }
        @Override protected boolean canPowerSide(Direction side) { return side == face; }
        @Override public void refreshSides() {
            if (level instanceof ServerLevel && installed()) {
                level.invalidateCapabilities(worldPosition);
                level.updateNeighborsAt(worldPosition, DataCableBlockEntity.this.getBlockState().getBlock());
            }
        }
        @Override public void setChanged() { DataCableBlockEntity.this.setChanged(); }
        @Override public boolean isRemoved() { return super.isRemoved() || DataCableBlockEntity.this.isRemoved() || ports.get(face) != this; }
        @Override public boolean installed() { return !isRemoved(); }
        @Override public Component getDisplayName() { return label().isEmpty() ? Component.translatable("item.jasm.thin_access_port") : Component.literal(label()); }
    }
}
