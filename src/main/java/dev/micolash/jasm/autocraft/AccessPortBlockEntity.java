package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PlayerNames;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.transfer.PortOperations;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * An Access Port. Every block touching it that takes items (a furnace, a modded machine, a multiblock's input) is a
 * machine the network can use; cables and other crafting blocks are not. Which sides have machines is looked up each
 * time, so machines placed or removed later count at once.
 *
 * <p>While a job has sent a set of ingredients into one of its machines, that side is locked to the job and the port
 * takes in returns for the job. Full-block ports also accept ordinary items and forward them to a paired Deck.
 * Thin ports accept items through their mounted face. Automation cannot pull items out.
 */
public class AccessPortBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOTS = 9;
    public static final int BUFFER_SLOTS = 8;
    public static final int POWER_SLOT = SLOTS;
    public static final int DECK_IN = POWER_SLOT + 1;
    public static final int DECK_OUT = DECK_IN + 1;
    public static final int SPEED_START = DECK_OUT + 1;
    public static final int INVENTORY_SIZE = SPEED_START + PortOperations.UPGRADE_SLOTS;
    public static final int CAPACITY = 5_000;
    private static final int[] BUFFER = {0, 1, 2, 3, 4, 5, 6, 7};
    /** Ticks between checks that the jobs holding locks still exist. */
    private static final int LOCK_CHECK = 40;

    /** Which job's sets are in the machine on one side, and which of its steps they are for. */
    public record Lock(UUID job, int step) {}

    private record SavedLock(Direction side, UUID job, int step) {
        static final Codec<SavedLock> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Direction.CODEC.fieldOf("side").forGetter(SavedLock::side),
                        UUIDUtil.CODEC.fieldOf("job").forGetter(SavedLock::job),
                        Codec.INT.fieldOf("step").forGetter(SavedLock::step))
                .apply(i, SavedLock::new));
    }

    private NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final Map<Direction, Lock> locks = new EnumMap<>(Direction.class);
    /** A name the player gave the port; empty for the machines' own names. */
    private String label = "";
    private boolean blockingMode;
    private @Nullable UUID destinationDeck;
    private @Nullable UUID pendingLinker;
    private final PortOperations operations = new PortOperations();

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case AccessPortMenu.DATA_ENERGY_LOW -> energy.getAmountAsInt() & 0xFFFF;
                case AccessPortMenu.DATA_ENERGY_HIGH -> energy.getAmountAsInt() >>> 16;
                case AccessPortMenu.DATA_RUNNING -> running() ? 1 : 0;
                case AccessPortMenu.DATA_LOCKED -> locks.size();
                case AccessPortMenu.DATA_DEFAULT_DECK -> defaultDeck() ? 1 : 0;
                case AccessPortMenu.DATA_LINKED -> deckLinked() ? 1 : 0;
                case AccessPortMenu.DATA_BLOCKING -> blockingMode ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return AccessPortMenu.DATA_COUNT;
        }
    };

    public AccessPortBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ACCESS_PORT_ENTITY.get(), pos, state, CAPACITY);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AccessPortBlockEntity port) {
        port.payForTick();
        port.sendPower();
        port.processDeckLink();
        port.forwardItems();
        if (level.getGameTime() % 10 == 0) {
            // A machine can start taking items without its block changing (a modded one finishing its build, say).
            port.refreshSides();
        }
        if (!port.locks.isEmpty() && level.getGameTime() % LOCK_CHECK == 0) {
            // A job that ended without letting go (its server was broken in an unloaded spot, say) lets go now.
            AutocraftState autocraft = AutocraftState.get(((ServerLevel) level).getServer());
            for (UUID id : port.lockedJobs()) {
                AutocraftState.Job job = autocraft.job(id).orElse(null);
                if (job == null || job.finished()) {
                    port.unlockJob(id);
                }
            }
        }
    }

    public boolean hasPowerUpgrade() {
        return getItem(POWER_SLOT).is(JasmItems.POWER_UPGRADE.get());
    }

    public int transferRate() {
        int upgrades = 0;
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (getItem(SPEED_START + i).is(JasmItems.SPEED_UPGRADE.get())) upgrades++;
        }
        return PortOperations.itemsPerOperation(upgrades);
    }

    public int transferBudget() {
        return level != null && running() && !networkBlocked() ? operations.available(level.getGameTime(), transferRate()) : 0;
    }

    public boolean canSendBatch(int count) {
        return level != null && running() && !networkBlocked() && operations.canSendBatch(level.getGameTime(), transferRate(), count);
    }

    public void transferred(int count) {
        if (level != null) operations.transferred(level.getGameTime(), count);
    }

    public boolean acceptsOrdinaryItems() { return true; }

    public boolean defaultDeck() { return destinationDeck == null; }

    private List<UUID> terminalIds(CableNetwork network) {
        return network.machines(EncodingTerminalBlockEntity.class).stream().map(EncodingTerminalBlockEntity::ensureId).toList();
    }

    protected AutocraftState.@Nullable Pairing destination(CableNetwork network) {
        var state = AutocraftState.get(((ServerLevel) level).getServer());
        List<UUID> terminals = terminalIds(network);
        var pairing = destinationDeck == null ? state.pairedPlayer(terminals, owner()).orElse(null)
                : state.pairing(destinationDeck).filter(p -> terminals.contains(p.terminal())).orElse(null);
        if (pairing == null || owner() == null || !pairing.player().equals(owner())
                && !MachineAccess.trustedBy(network, owner(), pairing.player())) return null;
        return pairing;
    }

    public void queueDeckLink(Player player) {
        if (!MachineAccess.canUse(this, player)) return;
        pendingLinker = getItem(DECK_IN).isEmpty() ? null : player.getUUID();
        setChanged();
        processDeckLink();
    }

    public void processDeckLink() {
        if (!(level instanceof ServerLevel serverLevel) || networkBlocked()
                || pendingLinker == null || !getItem(DECK_OUT).isEmpty()) return;
        ItemStack deck = getItem(DECK_IN);
        if (!DeckItem.isDeck(deck)) {
            pendingLinker = null;
            setChanged();
            return;
        }
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        if (network == null || owner() == null || !pendingLinker.equals(owner())
                && !MachineAccess.trustedBy(network, owner(), pendingLinker)) return;
        UUID id = deck.get(JasmComponents.DECK_ID.get());
        var pairing = id == null ? null : AutocraftState.get(serverLevel.getServer()).pairing(id).orElse(null);
        if (pairing == null || !terminalIds(network).contains(pairing.terminal())
                || !pairing.terminal().equals(deck.get(JasmComponents.DECK_NETWORK.get()))
                || !pairing.player().equals(owner()) && !MachineAccess.trustedBy(network, owner(), pairing.player())) return;
        destinationDeck = pairing.player().equals(owner()) ? null : id;
        items.set(DECK_IN, ItemStack.EMPTY);
        items.set(DECK_OUT, deck);
        pendingLinker = null;
        setChanged();
    }

    public boolean resetDeck(Player player) {
        if (!getItem(DECK_IN).isEmpty() || !MachineAccess.canUse(this, player)) return false;
        destinationDeck = null;
        setChanged();
        return true;
    }

    /** Whose Deck the port delivers to: the owner's, or the player whose Deck was linked in its place. */
    public String linkedPlayerName() {
        if (destinationDeck == null || !(level instanceof ServerLevel serverLevel)) return ownerName();
        var pairing = AutocraftState.get(serverLevel.getServer()).pairing(destinationDeck).orElse(null);
        return pairing == null ? "" : PlayerNames.of(serverLevel.getServer(), pairing.player(), Networks.at(serverLevel, worldPosition));
    }

    public boolean deckLinked() {
        if (!(level instanceof ServerLevel serverLevel) || networkBlocked()) return false;
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        var pairing = network == null ? null : destination(network);
        return pairing != null && pairing.deck().equals(getItem(DECK_OUT).get(JasmComponents.DECK_ID.get()));
    }

    /** Items a processing job still expects stay here for its server to collect first. */
    private void forwardItems() {
        if (!(level instanceof ServerLevel serverLevel) || stopped() || !running() || networkBlocked()) return;
        int budget = transferBudget();
        if (budget <= 0) return;
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        var pairing = network == null ? null : destination(network);
        if (pairing == null) return;
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(pairing.player());
        if (player == null) return;
        ItemStack deck = Jobs.findDeck(player, pairing.deck());
        if (deck.isEmpty() && pairing.deck().equals(getItem(DECK_OUT).get(JasmComponents.DECK_ID.get()))) deck = getItem(DECK_OUT);
        if (deck.isEmpty() || !DeckItem.worksIn(deck, player.level())
                || !DeckItem.worksIn(deck, serverLevel)
                || !pairing.terminal().equals(deck.get(JasmComponents.DECK_NETWORK.get()))) return;
        Set<ItemResource> expected = Jobs.expectedPortReturns(serverLevel, this);
        if (expected == null) return;
        for (int i = 0; i < SLOTS; i++) {
            if (!items.get(i).isEmpty() && expected.contains(ItemResource.of(items.get(i)))) return;
        }
        var store = WaferStore.get(serverLevel.getServer());
        var storage = DeckStorage.checked(store, deck, player);
        // The old ninth intake slot can still contain saved returns; drain it without accepting new items there.
        var incoming = new java.util.LinkedHashMap<ItemResource, Long>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && !expected.contains(ItemResource.of(stack))) incoming.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
        }
        var accepted = storage.depositAmounts(incoming, budget);
        transferred((int) accepted.values().stream().mapToLong(Long::longValue).sum());
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || expected.contains(ItemResource.of(stack))) continue;
            ItemResource key = ItemResource.of(stack);
            long moved = Math.min(stack.getCount(), accepted.getOrDefault(key, 0L));
            if (moved > 0) {
                accepted.put(key, accepted.get(key) - moved);
                stack.shrink((int) moved);
                setChanged();
            }
        }
        Jobs.refreshOpenDeck(player, deck);
    }

    @Override
    protected void onOwnerChanged(@Nullable UUID previous) {
        destinationDeck = null;
        pendingLinker = null;
    }

    protected boolean canPowerSide(Direction side) { return true; }

    /** The upgrade sends spare charge to nearby machines, even without a crafting job. */
    protected void sendPower() {
        if (level == null || networkBlocked() || !hasPowerUpgrade() || stopped()) return;
        for (Direction side : Direction.values()) {
            int available = energy.getAmountAsInt() - drainPerTick();
            if (available <= 0) return;
            if (!canPowerSide(side)) continue;
            BlockPos targetPos = worldPosition.relative(side);
            if (!level.isLoaded(targetPos)) continue;
            var targetBlock = level.getBlockState(targetPos).getBlock();
            var entity = level.getBlockEntity(targetPos);
            // JASM blocks share power through their own network.
            if (targetBlock instanceof DataCableBlock || entity instanceof MachineBlockEntity
                    || entity instanceof ArchiveBlockEntity || entity instanceof dev.micolash.jasm.network.NetworkPowerSource) continue;
            var target = level.getCapability(Capabilities.Energy.BLOCK, targetPos, side.getOpposite());
            if (target == null) continue;
            try (Transaction tx = Transaction.openRoot()) {
                int accepted = target.insert(available, tx);
                if (accepted > 0 && energy.extract(accepted, tx) == accepted) tx.commit();
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel serverLevel) {
            for (UUID job : lockedJobs()) {
                Jobs.writeNow(serverLevel.getServer(), job);
            }
        }
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.PORT_DRAIN.getAsInt();
    }

    public boolean installed() {
        return !isRemoved() && level != null && level.getBlockEntity(worldPosition) == this;
    }

    // --- the machines ---

    /** The sides with a machine touching them, in a fixed order. */
    public List<Direction> machineSides() {
        List<Direction> sides = new ArrayList<>();
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (hasMachine(side)) {
                    sides.add(side);
                }
            }
        }
        return sides;
    }

    /** Updates the port's shape to what touches each side now: a machine (idle or holding a job's items), the network, or nothing. */
    public void refreshSides() {
        if (!(level instanceof ServerLevel) || !(getBlockState().getBlock() instanceof AccessPortBlock)) {
            return;
        }
        BlockState state = getBlockState();
        BlockState shown = state;
        for (Direction side : Direction.values()) {
            shown = shown.setValue(AccessPortBlock.SIDES.get(side), sideLooks(side));
        }
        if (shown != state) {
            level.setBlock(worldPosition, shown, Block.UPDATE_CLIENTS);
        }
    }

    private PortSide sideLooks(Direction side) {
        if (hasMachine(side)) {
            return locks.containsKey(side) ? PortSide.BUSY : PortSide.MACHINE;
        }
        BlockPos pos = worldPosition.relative(side);
        BlockState there = level.getBlockState(pos);
        boolean hopperIn = there.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                && there.getValue(net.minecraft.world.level.block.HopperBlock.FACING) == side.getOpposite();
        boolean network = there.getBlock() instanceof DataCableBlock || level.getBlockEntity(pos) instanceof MachineBlockEntity
                || level.getBlockEntity(pos) instanceof ArchiveBlockEntity;
        return hopperIn || network ? PortSide.LINK : PortSide.NONE;
    }

    /**
     * Whether a machine touches {@code side}: a block that takes items, but not a hopper pointing into the port (that
     * one brings results back).
     */
    public boolean hasMachine(Direction side) {
        if (level == null) {
            return false;
        }
        BlockPos pos = worldPosition.relative(side);
        BlockState there = level.getBlockState(pos);
        if (there.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                && there.getValue(net.minecraft.world.level.block.HopperBlock.FACING) == side.getOpposite()) {
            return false;
        }
        return Machines.inlet(level, pos, side.getOpposite()) != null;
    }

    /**
     * The name of the machine on {@code side}: the block's own name, or the port's name if it has one (with the
     * block's after it when the port has more than one machine).
     */
    public Component machineName(Direction side) {
        Component block = Machines.blockName(level, worldPosition.relative(side));
        if (label.isEmpty()) {
            return block;
        }
        return machineSides().size() > 1 ? Component.literal(label + ": ").append(block) : Component.literal(label);
    }

    /** Every machine's name, for screens: "Furnace, Barrel", or a note that there is none. */
    public Component machineNames() {
        List<Direction> sides = machineSides();
        if (sides.isEmpty()) {
            return Component.translatable("screen.jasm.port.no_machine");
        }
        net.minecraft.network.chat.MutableComponent names = Component.empty();
        for (int i = 0; i < sides.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(Machines.blockName(level, worldPosition.relative(sides.get(i))));
        }
        return names;
    }

    // --- the locks ---

    public @Nullable Lock lock(Direction side) {
        return locks.get(side);
    }

    /** The jobs holding a side of this port. */
    public Set<UUID> lockedJobs() {
        Set<UUID> jobs = new LinkedHashSet<>();
        locks.values().forEach(l -> jobs.add(l.job()));
        return jobs;
    }

    public boolean locked() {
        return !locks.isEmpty();
    }

    /** Whether step {@code step} of job {@code job} may send sets to the machine on {@code side}: nobody else holds it. */
    public boolean freeFor(Direction side, UUID job, int step) {
        Lock lock = locks.get(side);
        return lock == null || lock.job().equals(job) && lock.step() == step;
    }

    public void lock(Direction side, UUID job, int step) {
        Lock wanted = new Lock(job, step);
        if (!wanted.equals(locks.put(side, wanted))) {
            setChanged();
            refreshSides();
        }
    }

    public void unlock(Direction side) {
        if (locks.remove(side) != null) {
            setChanged();
            refreshSides();
        }
    }

    /** Lets go of every side {@code job} holds. */
    public void unlockJob(UUID job) {
        if (locks.values().removeIf(l -> l.job().equals(job))) {
            setChanged();
            refreshSides();
        }
    }

    // --- the name ---

    public String label() {
        return label;
    }

    public boolean blockingMode() { return blockingMode; }

    public void setBlockingMode(boolean enabled) {
        blockingMode = enabled;
        setChanged();
    }

    public void setLabel(String label) {
        String cleaned = label.strip();
        this.label = cleaned.length() > AccessPortMenu.MAX_NAME ? cleaned.substring(0, AccessPortMenu.MAX_NAME) : cleaned;
        setChanged();
    }

    // --- the intake ---

    /** Takes up to {@code most} of {@code key} out of the intake. Returns how many. */
    long take(ItemResource key, long most) {
        most = Math.min(most, transferBudget());
        long taken = 0;
        for (int i = 0; i < SLOTS && taken < most; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && ItemResource.of(stack).equals(key)) {
                int n = (int) Math.min(stack.getCount(), most - taken);
                stack.shrink(n);
                taken += n;
            }
        }
        if (taken > 0) {
            transferred((int) taken);
            setChanged();
        }
        return taken;
    }

    /** What the intake holds, by item. */
    Map<ItemResource, Long> intake() {
        Map<ItemResource, Long> held = new java.util.LinkedHashMap<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                held.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return held;
    }

    /** Items can enter the buffer even while the port is idle or unpowered. */
    public boolean open() {
        return acceptsOrdinaryItems() || !locks.isEmpty() && running();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= SPEED_START && slot < INVENTORY_SIZE) return stack.is(JasmItems.SPEED_UPGRADE.get());
        if (slot == POWER_SLOT) return stack.is(JasmItems.POWER_UPGRADE.get());
        if (slot == DECK_IN) return DeckItem.isDeck(stack);
        return slot >= 0 && slot < BUFFER_SLOTS;
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return BUFFER;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return slot >= 0 && slot < BUFFER_SLOTS && open();
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    public ContainerData data() {
        return data;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public Component getName() {
        return label.isEmpty() ? super.getName() : Component.literal(label);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new AccessPortMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    /** What the screen needs when it opens: where the port is, its name and its machines'. */
    public void writeOpening(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(worldPosition);
        buf.writeUtf(label, AccessPortMenu.MAX_NAME);
        AccessPortMenu.MachineView.STREAM_CODEC.apply(net.minecraft.network.codec.ByteBufCodecs.list(6))
                .encode(buf, AccessPortMenu.connectedMachines(this));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        List<SavedLock> saved = new ArrayList<>();
        locks.forEach((side, lock) -> saved.add(new SavedLock(side, lock.job(), lock.step())));
        output.store("locks", SavedLock.CODEC.listOf(), saved);
        output.putString("label", label);
        output.putBoolean("blocking_mode", blockingMode);
        output.storeNullable("destination_deck", UUIDUtil.CODEC, destinationDeck);
        output.storeNullable("pending_linker", UUIDUtil.CODEC, pendingLinker);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        locks.clear();
        input.read("locks", SavedLock.CODEC.listOf()).orElse(List.of()).forEach(l -> locks.put(l.side(), new Lock(l.job(), l.step())));
        label = input.getStringOr("label", "");
        blockingMode = input.getBooleanOr("blocking_mode", false);
        destinationDeck = input.read("destination_deck", UUIDUtil.CODEC).orElse(null);
        pendingLinker = input.read("pending_linker", UUIDUtil.CODEC).orElse(null);
    }

    /** The mined item keeps the port's name. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        // Inventory contents drop separately when either form is broken.
        components.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (!label.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component name = components.get(DataComponents.CUSTOM_NAME);
        label = name == null ? "" : name.getString();
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("label");
    }
}
