package dev.micolash.jasm.archive;

import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * A placed Archive: which Archive record it is, a copy of its owner (to rebuild a lost record), and its FE buffer.
 * Links, trust and placement live in the record, so they follow the Archive when it is mined and placed again.
 */
public class ArchiveBlockEntity extends BlockEntity implements MenuProvider {
    private final ArchiveTier tier;
    private final SimpleEnergyHandler energy;
    private @Nullable UUID archiveId;
    private @Nullable UUID ownerId;
    private String ownerName = "";
    /** Whether this block has been checked against its record since it was placed or loaded. */
    private boolean placementChecked;

    public ArchiveBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ARCHIVE_ENTITY.get(), pos, state);
        this.tier = ((ArchiveBlock) state.getBlock()).tier();
        this.energy = new SimpleEnergyHandler(tier.energyBuffer(), tier.energyBuffer(), tier.energyBuffer()) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
    }

    /**
     * Checks a loaded block against its record once the wafer store is open (never during world load), and uses
     * the tier's running cost.
     */
    static void serverTick(Level level, BlockPos pos, BlockState state, ArchiveBlockEntity archive) {
        if (!archive.placementChecked && WaferStore.ifOpen(level.getServer()) != null) {
            ArchivePlacement.loaded(archive, (ServerLevel) level);
        }
        archive.drain();
    }

    /**
     * One tick of running cost. An Archive that runs dry keeps its owner, trust and links; it just can't link or
     * recover until it is charged again.
     */
    public void drain() {
        int amount = energy.getAmountAsInt();
        if (amount > 0) {
            energy.set(Math.max(0, amount - tier.drainPerTick()));
        }
    }

    public ArchiveTier tier() {
        return tier;
    }

    public SimpleEnergyHandler energy() {
        return energy;
    }

    public @Nullable UUID archiveId() {
        return archiveId;
    }

    public @Nullable UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    /** This block's record, if it has one and the store is open. */
    public @Nullable ArchiveRecord record() {
        WaferStore store = level == null || level.getServer() == null ? null : WaferStore.ifOpen(level.getServer());
        return store == null || archiveId == null ? null : store.state().archive(archiveId).orElse(null);
    }

    /** Binds this block to a record and copies its owner. Called by {@link ArchivePlacement}. */
    void bind(ArchiveRecord record) {
        archiveId = record.id();
        ownerId = record.owner();
        ownerName = record.ownerName();
        placementChecked = true;
        setChanged();
    }

    void markChecked() {
        placementChecked = true;
    }

    /** Opens the screen for the owner and trusted players; everyone else is told whose Archive it is. */
    public void open(ServerPlayer player) {
        ArchiveRecord record = record();
        if (record == null) {
            player.sendOverlayMessage(Component.translatable("message.jasm.archive.not_ready"));
        } else if (!record.isAuthorized(player.getUUID())) {
            player.sendOverlayMessage(Component.translatable("message.jasm.archive.no_access", record.ownerName()));
        } else {
            player.openMenu(this, buf -> buf.writeBlockPos(worldPosition));
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            ArchivePlacement.removed(this, serverLevel);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("archive", UUIDUtil.CODEC, archiveId);
        output.storeNullable("owner", UUIDUtil.CODEC, ownerId);
        output.putString("owner_name", ownerName);
        output.putInt("energy", energy.getAmountAsInt());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        archiveId = input.read("archive", UUIDUtil.CODEC).orElse(null);
        ownerId = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, tier.energyBuffer()));
    }

    /** The mined item carries the identity and the charge. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (archiveId != null) {
            components.set(JasmComponents.ARCHIVE_IDENTITY.get(), archiveId);
        }
        if (energy.getAmountAsInt() > 0) {
            components.set(JasmComponents.ENERGY.get(), energy.getAmountAsInt());
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        archiveId = components.get(JasmComponents.ARCHIVE_IDENTITY.get());
        energy.set(Math.clamp(components.getOrDefault(JasmComponents.ENERGY.get(), 0), 0, tier.energyBuffer()));
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        output.discard("archive");
        output.discard("energy");
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ArchiveMenu(containerId, inventory, this);
    }
}
