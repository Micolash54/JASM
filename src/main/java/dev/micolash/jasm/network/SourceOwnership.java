package dev.micolash.jasm.network;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.UUID;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The network owner of a power source, kept when it is mined or disconnected. */
public final class SourceOwnership {
    private final BlockEntity block;
    private @Nullable MachineOwner owner;
    private boolean blocked;

    public SourceOwnership(BlockEntity block) {
        this.block = block;
    }

    public @Nullable UUID owner() {
        return owner == null ? null : owner.id();
    }

    public String name() {
        return owner == null ? "" : owner.name();
    }

    public boolean canUse(Player player) {
        if (owner == null || owner.id().equals(player.getUUID())) {
            return true;
        }
        if (!(block.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        CableNetwork network = Networks.at(level, block.getBlockPos());
        return network != null && MachineAccess.trustedBy(network, owner.id(), player.getUUID());
    }

    public boolean blocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        if (this.blocked != blocked) {
            this.blocked = blocked;
            block.setChanged();
        }
    }

    public void adopt(UUID id, String name) {
        if (id.equals(owner())) {
            return;
        }
        owner = new MachineOwner(id, name);
        block.setChanged();
        if (block.getLevel() instanceof ServerLevel level) {
            CableClaims.get(level).rememberOwner(id, name);
            Networks.ownerChanged(level, block.getBlockPos());
        }
    }

    public void save(ValueOutput output) {
        output.storeNullable("network_owner", MachineOwner.CODEC, owner);
        output.putBoolean("network_blocked", blocked);
    }

    public void load(ValueInput input) {
        owner = input.read("network_owner", MachineOwner.CODEC).orElse(null);
        blocked = input.getBooleanOr("network_blocked", false);
    }

    public void collect(DataComponentMap.Builder components) {
        if (owner != null) {
            components.set(JasmComponents.OWNER.get(), owner);
        }
    }

    public void apply(DataComponentGetter components) {
        owner = components.get(JasmComponents.OWNER.get());
    }
}
