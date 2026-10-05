package dev.micolash.jasm.archive;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.NetworkEnergy;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashMap;
import java.util.List;
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
    private boolean networkBlocked;
    private final SimpleContainer deckSlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            ArchiveBlockEntity.this.setChanged();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    };
    private @Nullable UUID pendingLinker;
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
        this.energy = new NetworkEnergy(tier.energyBuffer()) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                if (level != null) level.blockEntityChanged(worldPosition);
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
        // Checking the link means walking the network for terminals: once a second is plenty, and each Archive takes
        // a different tick so they don't all walk at once. Anything a player does checks straight away.
        if ((level.getGameTime() + pos.asLong()) % 20 == 0) {
            archive.refreshDeckLink();
        }
        archive.processDeckLink();
    }

    /**
     * One tick of running cost. An Archive that runs dry keeps its owner, trust and links; it just can't link or
     * recover until it is charged again. On a full network it rests and uses nothing.
     */
    public void drain() {
        if (networkStopped()) {
            return;
        }
        int amount = energy.getAmountAsInt();
        if (amount > 0) {
            energy.set(Math.max(0, amount - tier.drainPerTick()));
        }
    }

    /** Whether its network holds more machines than its brain allows: it can't link or recover then. */
    public boolean networkStopped() {
        return level instanceof ServerLevel s && Networks.at(s, worldPosition) instanceof CableNetwork n && n.limitState().stopped();
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

    public boolean networkBlocked() {
        return networkBlocked;
    }

    public void setNetworkBlocked(boolean blocked) {
        if (networkBlocked != blocked) {
            networkBlocked = blocked;
            setChanged();
        }
    }

    public void adoptOwner(UUID owner, String name) {
        ArchiveRecord record = record();
        if (record == null || owner.equals(ownerId) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        clearBackups();
        var state = WaferStore.get(serverLevel.getServer()).state();
        state.setArchiveOwner(record, owner, name);
        state.setArchiveDeck(record, null, null, true);
        state.setArchiveNetwork(record, null);
        pendingLinker = null;
        bind(record);
        WaferStore.get(serverLevel.getServer()).state().saveNow(serverLevel.getServer());
    }

    public void clearBackups() {
        ArchiveRecord record = record();
        if (record == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        WaferStore store = WaferStore.get(serverLevel.getServer());
        List<Long> linked = List.copyOf(record.linked());
        store.state().discardArchiveLinks(record);
        store.state().saveNow(serverLevel.getServer());
        for (long serial : linked) {
            store.bySerial(serial).filter(wafer -> record.id().equals(wafer.archiveId()))
                    .ifPresent(wafer -> store.setLink(wafer, null, wafer.lastKnownName(), null));
        }
    }

    public SimpleContainer deckSlots() {
        return deckSlots;
    }

    public boolean canManageDeck(Player player) {
        return ownerId != null && ownerId.equals(player.getUUID()) && MachineAccess.canUse(this, player);
    }

    public boolean canBackup(Player player) {
        refreshDeckLink();
        ArchiveRecord record = record();
        return record != null && player.getUUID().equals(record.defaultDeck() ? record.owner() : record.deckPlayer())
                && MachineAccess.canUse(this, player);
    }

    private List<UUID> terminalIds() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        return network == null
                ? List.of()
                : network.machines(EncodingTerminalBlockEntity.class).stream()
                        .map(EncodingTerminalBlockEntity::ensureId).toList();
    }

    /** Losing the physical Deck does not erase its registered player's recovery permission. */
    public void refreshDeckLink() {
        ArchiveRecord record = record();
        if (record == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        List<UUID> terminals = terminalIds();
        if (terminals.isEmpty()) {
            return;
        }
        var state = WaferStore.get(serverLevel.getServer()).state();
        if (record.network() != null && !terminals.contains(record.network())) {
            CableNetwork network = Networks.at(serverLevel, worldPosition);
            var previous = AutocraftState.get(serverLevel.getServer()).terminal(record.network()).orElse(null);
            if (network == null || !network.complete()
                    || previous != null && previous.placement().dimension().equals(serverLevel.dimension())
                            && !serverLevel.isLoaded(previous.placement().pos())) {
                return;
            }
            clearBackups();
            state.setArchiveDeck(record, null, null, true);
        }
        if (record.network() == null || !terminals.contains(record.network())) {
            state.setArchiveNetwork(record, terminals.getFirst());
            state.saveNow(serverLevel.getServer());
        }
        UUID player = record.defaultDeck() ? record.owner() : record.deckPlayer();
        if (player == null) {
            return;
        }
        AutocraftState.get(serverLevel.getServer()).pairedPlayer(terminals, player).ifPresent(pairing -> {
            if (!pairing.deck().equals(record.deck()) || !player.equals(record.deckPlayer())) {
                if (record.deckPlayer() != null && !player.equals(record.deckPlayer())) {
                    clearBackups();
                }
                state.setArchiveDeck(record, pairing.deck(), player, record.defaultDeck());
                state.saveNow(serverLevel.getServer());
            }
        });
    }

    public void queueDeckLink(Player player) {
        refreshDeckLink();
        if (canManageDeck(player) && deckSlots.getItem(0).getItem() instanceof DeckItem) {
            pendingLinker = player.getUUID();
            setChanged();
            processDeckLink();
        }
    }

    public void processDeckLink() {
        if (!(level instanceof ServerLevel serverLevel) || pendingLinker == null || !deckSlots.getItem(1).isEmpty()) {
            return;
        }
        ItemStack deck = deckSlots.getItem(0);
        UUID id = deck.get(JasmComponents.DECK_ID.get());
        ArchiveRecord record = record();
        if (record == null || !pendingLinker.equals(record.owner()) || !(deck.getItem() instanceof DeckItem)) {
            pendingLinker = null;
            setChanged();
            return;
        }
        if (id == null) {
            id = UUID.randomUUID();
            deck.set(JasmComponents.DECK_ID.get(), id);
        }
        var pairing = AutocraftState.get(serverLevel.getServer()).pairing(id).orElse(null);
        List<UUID> terminals = terminalIds();
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        UUID linkedPlayer = pairing == null ? record.owner() : pairing.player();
        boolean ownerDeck = linkedPlayer.equals(record.owner());
        if (!ownerDeck && (!terminals.contains(pairing.terminal()) || network == null
                || !MachineAccess.trustedBy(network, record.owner(), linkedPlayer))) {
            pendingLinker = null;
            setChanged();
            return;
        }
        UUID previousPlayer = record.defaultDeck() ? record.owner() : record.deckPlayer();
        if (previousPlayer != null && !previousPlayer.equals(linkedPlayer)) {
            clearBackups();
        }
        var state = WaferStore.get(serverLevel.getServer()).state();
        state.setArchiveDeck(record, id, linkedPlayer, ownerDeck);
        state.saveNow(serverLevel.getServer());
        deckSlots.setItem(0, ItemStack.EMPTY);
        deckSlots.setItem(1, deck);
        pendingLinker = null;
        setChanged();
    }

    public boolean resetDeck(Player player) {
        ArchiveRecord record = record();
        if (!canManageDeck(player) || record == null || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (record.deckPlayer() != null && !record.owner().equals(record.deckPlayer())) {
            clearBackups();
        }
        var state = WaferStore.get(serverLevel.getServer()).state();
        UUID keep = record.owner().equals(record.deckPlayer()) ? record.deck() : null;
        state.setArchiveDeck(record, keep, keep == null ? null : record.owner(), true);
        state.saveNow(serverLevel.getServer());
        refreshDeckLink();
        return true;
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
            // The linked wafers' records are read while the screen opens, so the first click on one doesn't wait.
            WaferStore.get(player.level().getServer()).prefetch(record.linked());
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
            Containers.dropContents(level, pos, deckSlots);
            deckSlots.clearContent();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("archive", UUIDUtil.CODEC, archiveId);
        output.storeNullable("owner", UUIDUtil.CODEC, ownerId);
        output.putString("owner_name", ownerName);
        output.putBoolean("network_blocked", networkBlocked);
        output.storeNullable("pending_linker", UUIDUtil.CODEC, pendingLinker);
        output.store("deck_input", ItemStack.OPTIONAL_CODEC, deckSlots.getItem(0));
        output.store("deck_output", ItemStack.OPTIONAL_CODEC, deckSlots.getItem(1));
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
        networkBlocked = input.getBooleanOr("network_blocked", false);
        pendingLinker = input.read("pending_linker", UUIDUtil.CODEC).orElse(null);
        deckSlots.setItem(0, input.read("deck_input", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        deckSlots.setItem(1, input.read("deck_output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
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
