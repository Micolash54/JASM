package dev.micolash.jasm.archive;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * A placed Archive: which Archive record it is, a copy of its owner (to rebuild a lost record), its FE buffer, and
 * each player's two wafer slots. Links, trust and placement live in the record, so they follow the Archive when it
 * is mined and placed again.
 */
public class ArchiveBlockEntity extends BlockEntity implements MenuProvider {
    private final ArchiveTier tier;
    private final SimpleEnergyHandler energy;
    private @Nullable UUID archiveId;
    private @Nullable UUID ownerId;
    private String ownerName = "";
    /** Whether this block has been checked against its record since it was placed or loaded. */
    private boolean placementChecked;
    /**
     * Each player's link and recovery slots. They are saved with the block, so a wafer left in them after a crash
     * or a logout is still there the next time that player opens this Archive. Nobody else sees them.
     */
    private final Map<UUID, SimpleContainer> slots = new HashMap<>();

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

    /** {@code player}'s two wafer slots at this Archive. */
    public SimpleContainer slotsOf(UUID player) {
        return slots.computeIfAbsent(player, id -> newSlots());
    }

    private SimpleContainer newSlots() {
        SimpleContainer container = new SimpleContainer(2) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public void setChanged() {
                ArchiveBlockEntity.this.setChanged();
            }
        };
        return container;
    }

    /** Opens the screen for the owner and players trusted on its network; everyone else is told whose Archive it is. */
    public void open(ServerPlayer player) {
        ArchiveRecord record = record();
        if (record == null) {
            player.sendOverlayMessage(Component.translatable("message.jasm.archive.not_ready"));
        } else if (!MachineAccess.canUse(this, player)) {
            player.sendOverlayMessage(Component.translatable("message.jasm.archive.no_access", record.ownerName()));
        } else {
            player.openMenu(this, buf -> buf.writeBlockPos(worldPosition));
        }
    }

    /** Whatever is left in anyone's slots drops on the ground. An open screen then has nothing left to hand back. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            ArchivePlacement.removed(this, serverLevel);
            for (SimpleContainer container : slots.values()) {
                Containers.dropContents(level, pos, container);
                container.clearContent();
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("archive", UUIDUtil.CODEC, archiveId);
        output.storeNullable("owner", UUIDUtil.CODEC, ownerId);
        output.putString("owner_name", ownerName);
        output.putInt("energy", energy.getAmountAsInt());
        ValueOutput.TypedOutputList<SavedSlots> saved = output.list("slots", SavedSlots.CODEC);
        slots.forEach((player, container) -> {
            if (!container.isEmpty()) {
                saved.add(new SavedSlots(player, container.getItem(0), container.getItem(1)));
            }
        });
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        archiveId = input.read("archive", UUIDUtil.CODEC).orElse(null);
        ownerId = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, tier.energyBuffer()));
        slots.clear();
        for (SavedSlots saved : input.listOrEmpty("slots", SavedSlots.CODEC)) {
            SimpleContainer container = slotsOf(saved.player());
            container.setItem(0, saved.link());
            container.setItem(1, saved.recovery());
        }
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

    private record SavedSlots(UUID player, ItemStack link, ItemStack recovery) {
        static final Codec<SavedSlots> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(SavedSlots::player),
                        ItemStack.OPTIONAL_CODEC.optionalFieldOf("link", ItemStack.EMPTY).forGetter(SavedSlots::link),
                        ItemStack.OPTIONAL_CODEC.optionalFieldOf("recovery", ItemStack.EMPTY).forGetter(SavedSlots::recovery))
                .apply(i, SavedSlots::new));
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
