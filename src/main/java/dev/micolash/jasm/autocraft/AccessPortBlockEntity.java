package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.dock.DeckReach;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PlayerNames;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.transfer.RedstoneUpgradeItem;
import dev.micolash.jasm.transfer.SpeedUpgradeItem;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
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
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * An Access Port. Every block touching it that takes items (a furnace, a modded machine, a multiblock's input) is a
 * machine the network can use; cables and crafting blocks without an I/O grid are not. Which sides have machines is
 * looked up each time, so machines placed or removed later count at once.
 *
 * <p>While a job has sent a set of ingredients into one of its machines, that side is locked to the job and the port
 * takes in returns for the job. Full-block ports also accept ordinary items and forward them to a paired Deck.
 * Thin ports accept items through their mounted face. Automation cannot pull items out. Fluid pushed in through the
 * same faces goes straight to a job waiting for it or the network's storage, and only as much as fits.
 */
public class AccessPortBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOTS = 9;
    public static final int BUFFER_SLOTS = 8;
    public static final int POWER_SLOT = SLOTS;
    public static final int DECK_IN = POWER_SLOT + 1;
    public static final int DECK_OUT = DECK_IN + 1;
    public static final int SPEED_START = DECK_OUT + 1;
    public static final int INVENTORY_SIZE = SPEED_START + PortOperations.UPGRADE_SLOTS;
    /**
     * A Passive port's hidden buffer: the sets it holds for pipes to pull out. These slots come after the port's own
     * slots and are left out of its size, so screens and anything that walks the container never see them; only the
     * faces open them (see {@link #getSlotsForFace}).
     */
    public static final int HELD_START = INVENTORY_SIZE;
    public static final int HELD_SLOTS = 9;
    /** The side a Passive port's sets are locked under: it has no machines, so any side would do. */
    public static final Direction HELD_SIDE = Direction.DOWN;
    /** Most processing recipes one port serves. */
    public static final int MAX_RECIPES = 16;
    private static final int[] BUFFER = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] BUFFER_AND_HELD = java.util.stream.IntStream.concat(Arrays.stream(BUFFER),
            java.util.stream.IntStream.range(HELD_START, HELD_START + HELD_SLOTS)).toArray();
    private static final Direction[] SIDES = Direction.values();
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

    private NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE + HELD_SLOTS, ItemStack.EMPTY);
    private final Map<Direction, Lock> locks = new EnumMap<>(Direction.class);
    /** A name the player gave the port; empty for the machines' own names. */
    private String label = "";
    private boolean blockingMode;
    /** What a Redstone Upgrade in the column does; kept while the upgrade is out, but only followed while it is in. */
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @Nullable UUID destinationDeck;
    private @Nullable UUID pendingLinker;
    private final PortOperations operations = new PortOperations();
    /** The kinds of machine the port serves, in the order they were picked; empty until one is set. */
    private final Set<Block> machineTypes = new LinkedHashSet<>();
    /**
     * Passive: the port sends no sets into machines. Each set waits in its hidden buffer for pipes to pull out, and the
     * results come back in as usual. Only the full Access Port has it.
     */
    private boolean passive;
    /** What stood on each side when the type was last settled, so a later look only does the work when something changed. */
    private final @Nullable Block[] around = new Block[SIDES.length];
    /** The processing recipes the port serves, in the order they were picked. Never sent along with block updates. */
    private final Set<ProcessingCard> recipes = new LinkedHashSet<>();
    /**
     * Per side, since when (game time) the port has been after the machine there; 0 when it isn't. When ports meet at one
     * machine, the one after it longest keeps it.
     */
    private final long[] wantedSince = new long[SIDES.length];
    /** The sides whose machine another port keeps, one bit per side. */
    private int takenSides;
    /** Set when who keeps the machines may have changed; settled on the next look. */
    private boolean claimsStale = true;

    /** What machines, hoppers and pipes push in. */
    private final PortIntake intake = new PortIntake(this);
    /** How the Power Upgrade passes power on: the port itself holds none. */
    private final PortPower power = new PortPower(this);
    private @Nullable List<EnergyHandler> inlets;
    /** What other mods' machines take power through, one per side, made when first needed. */
    private final @Nullable BlockCapabilityCache<EnergyHandler, @Nullable Direction>[] energyCaps = newCaches();

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case AccessPortMenu.DATA_FLOW_LOW -> ContainerWords.low(powerFlow());
                case AccessPortMenu.DATA_FLOW_HIGH -> ContainerWords.high(powerFlow());
                case AccessPortMenu.DATA_RUNNING -> running() ? 1 : 0;
                case AccessPortMenu.DATA_LOCKED -> locks.size();
                case AccessPortMenu.DATA_DEFAULT_DECK -> defaultDeck() ? 1 : 0;
                case AccessPortMenu.DATA_LINKED -> deckLinked() ? 1 : 0;
                case AccessPortMenu.DATA_BLOCKING -> blockingMode ? 1 : 0;
                case AccessPortMenu.DATA_PAUSED -> redstonePaused() ? 1 : 0;
                case AccessPortMenu.DATA_REDSTONE -> redstoneMode.ordinal();
                case AccessPortMenu.DATA_TAKEN -> anyTaken() ? 1 : 0;
                case AccessPortMenu.DATA_PASSIVE -> passive ? 1 : 0;
                case AccessPortMenu.DATA_MODE_KEY -> !canBePassive() ? AccessPortMenu.MODE_KEY_NONE
                        : modeSwitchable() ? AccessPortMenu.MODE_KEY_ON : AccessPortMenu.MODE_KEY_GREYED;
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
        super(JasmBlocks.ACCESS_PORT_ENTITY.get(), pos, state, 0);
    }

    protected AccessPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, 0);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable BlockCapabilityCache<EnergyHandler, @Nullable Direction>[] newCaches() {
        return new BlockCapabilityCache[SIDES.length];
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AccessPortBlockEntity port) {
        port.checkStopped();
        if (port.claimsStale) {
            port.settleClaims();
        }
        port.processDeckLink();
        port.forwardItems();
        port.tickIntake();
        if (level.getGameTime() % 10 == 0) {
            // A machine can start taking items without its block changing (a modded one finishing its build, say).
            port.settleType(true);
            port.settleClaims();
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
        if (port.locks.isEmpty() && (level.getGameTime() + pos.asLong()) % LOCK_CHECK == 0 && !port.heldEmpty()) {
            // Held items whose job ended without taking them back (it was gone when the port let go) go to the network.
            port.returnHeldToNetwork((ServerLevel) level);
        }
    }

    public boolean hasRedstoneUpgrade() {
        return RedstoneUpgradeItem.elsewhere(this, SPEED_START, INVENTORY_SIZE, -1);
    }

    public RedstoneMode redstoneMode() {
        return redstoneMode;
    }

    public void setRedstoneMode(RedstoneMode mode) {
        redstoneMode = mode;
        setChanged();
    }

    /**
     * Whether the Redstone Upgrade's mode stops the port now. Ports in one block space read the same signal, each with its
     * own mode. Only looked at with the upgrade in, so other ports never read the signal.
     */
    public boolean redstonePaused() {
        return level != null && hasRedstoneUpgrade() && !redstoneMode.allows(level.hasNeighborSignal(worldPosition));
    }

    public boolean hasPowerUpgrade() {
        return getItem(POWER_SLOT).is(JasmItems.POWER_UPGRADE.get());
    }

    public int speedUpgrades() {
        return SpeedUpgradeItem.count(this, SPEED_START, SPEED_START + PortOperations.UPGRADE_SLOTS);
    }

    public int transferRate() {
        return PortOperations.itemsPerOperation(speedUpgrades());
    }

    private boolean working() {
        return level != null && !stopped() && !networkBlocked() && !redstonePaused();
    }

    /** What the port's own work may move now. */
    public int transferBudget() {
        return working() ? operations.available(level.getGameTime(), transferRate(), saving()) : 0;
    }

    /** What a push into the port may move now. */
    int pushBudget() {
        return working() ? operations.forPush(level.getGameTime(), transferRate(), saving()) : 0;
    }

    /**
     * What the port does with allowance it left unused. An Access Port keeps it (up to a second's worth) for anything it
     * does on a later tick: machines that push on their own timer rarely land on the tick an operation is due.
     */
    protected PortOperations.Saving saving() {
        return PortOperations.Saving.ALL;
    }

    public boolean canSendBatch(int count) {
        return working() && operations.canSendBatch(level.getGameTime(), transferRate(), count, saving());
    }

    public void transferred(int count) {
        if (level != null) operations.transferred(level.getGameTime(), count, saving());
    }

    /** A push moved {@code count} shares into the port. */
    void pushed(int count) {
        if (level != null) operations.pushed(level.getGameTime(), count, saving());
    }

    public boolean acceptsOrdinaryItems() { return true; }

    public boolean defaultDeck() { return destinationDeck == null; }

    private List<UUID> terminalIds(CableNetwork network) {
        return network.machines(EncodingTerminalBlockEntity.class).stream().map(EncodingTerminalBlockEntity::ensureId).toList();
    }

    protected AutocraftState.@Nullable Pairing destination(CableNetwork network) {
        var state = AutocraftState.get(((ServerLevel) level).getServer());
        List<UUID> terminals = terminalIds(network);
        var pairing = destinationDeck == null
                ? state.pairedPlayer(terminals, owner()).orElse(null)
                : state.pairing(destinationDeck).filter(p -> terminals.contains(p.terminal())).orElse(null);
        if (pairing == null || owner() == null || !pairing.player().equals(owner())
                && !MachineAccess.trustedBy(network, owner(), pairing.player()))
            return null;
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
                || pendingLinker == null || !getItem(DECK_OUT).isEmpty())
            return;
        ItemStack deck = getItem(DECK_IN);
        if (!DeckItem.isDeck(deck)) {
            pendingLinker = null;
            setChanged();
            return;
        }
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        if (network == null || owner() == null || !pendingLinker.equals(owner())
                && !MachineAccess.trustedBy(network, owner(), pendingLinker))
            return;
        UUID id = deck.get(JasmComponents.DECK_ID.get());
        var pairing = id == null ? null : AutocraftState.get(serverLevel.getServer()).pairing(id).orElse(null);
        if (pairing == null || !terminalIds(network).contains(pairing.terminal())
                || !pairing.terminal().equals(deck.get(JasmComponents.DECK_NETWORK.get()))
                || !pairing.player().equals(owner()) && !MachineAccess.trustedBy(network, owner(), pairing.player()))
            return;
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

    /** The Deck the port delivers to, with who acts for it (its player, or a stand-in when it is docked). */
    public record DeckTarget(ServerPlayer player, ItemStack deck) {}

    /**
     * The Deck the port delivers to, if it can be reached (in a working Docking Station, or with its player online: carried, or
     * in the port) and works here; else null.
     */
    public @Nullable DeckTarget deckTarget(ServerLevel serverLevel) {
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        var pairing = network == null ? null : destination(network);
        if (pairing == null) return null;
        DeckReach reach = DeckReach.find(serverLevel.getServer(), pairing.player(), pairing.deck(), getItem(DECK_OUT));
        if (reach == null) return null;
        ServerPlayer player = reach.actor();
        ItemStack deck = reach.deck();
        if (!DeckItem.worksIn(deck, player.level()) || !DeckItem.worksIn(deck, serverLevel)
                || !pairing.terminal().equals(deck.get(JasmComponents.DECK_NETWORK.get())))
            return null;
        return new DeckTarget(player, deck);
    }

    /** Items a processing job still expects stay here for its server to collect first. */
    private void forwardItems() {
        if (!(level instanceof ServerLevel serverLevel) || stopped() || networkBlocked()) return;
        int budget = transferBudget();
        if (budget <= 0) return;
        DeckTarget target = deckTarget(serverLevel);
        if (target == null) return;
        ServerPlayer player = target.player();
        ItemStack deck = target.deck();
        Predicate<ItemResource> expected = Jobs.expectedPortReturns(serverLevel, this);
        if (expected == null) return;
        for (int i = 0; i < SLOTS; i++) {
            if (!items.get(i).isEmpty() && expected.test(ItemResource.of(items.get(i)))) return;
        }
        var store = WaferStore.get(serverLevel.getServer());
        var storage = DeckStorage.checked(store, deck, player);
        // The old ninth intake slot can still contain saved returns; drain it without accepting new items there.
        var incoming = new LinkedHashMap<ItemResource, Long>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && !expected.test(ItemResource.of(stack)))
                incoming.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
        }
        var accepted = storage.depositAmounts(incoming, budget, DeckStorage.Excess.VOID);
        transferred((int) accepted.values().stream().mapToLong(Long::longValue).sum());
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || expected.test(ItemResource.of(stack))) continue;
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

    /** The upgrade powers only blocks of the kind of machine the port serves. */
    protected boolean canPowerSide(Direction side) {
        return !passive && picked(blockAt(side)) && !takenElsewhere(side);
    }

    /** The sides the Power Upgrade may ever reach: every side of a full port, the mounted face of a thin one. */
    protected List<Direction> powerFaces() {
        Direction face = face();
        return face == null ? List.of(SIDES) : List.of(face);
    }

    /**
     * What the Power Upgrade hands power to on {@code side} right now, or null: the machine there if it is of the kind
     * the port serves, other mods' machines and JASM ones alike, even without a crafting job. Nothing without the upgrade,
     * while the network is blocked or over its machine limit, or where the block isn't loaded. Redstone doesn't stop it.
     */
    @Nullable EnergyHandler powerTarget(Direction side) {
        if (!(level instanceof ServerLevel serverLevel) || !passesPower()) {
            return null;
        }
        settleType(false);
        if (!canPowerSide(side)) return null;
        BlockPos targetPos = worldPosition.relative(side);
        if (!level.isLoaded(targetPos)) return null;
        var entity = level.getBlockEntity(targetPos);
        // Cables hold no power and power sources take none.
        if (level.getBlockState(targetPos).getBlock() instanceof DataCableBlock || entity instanceof NetworkPowerSource) return null;
        // JASM machines and Archives are handed it directly, past what they show other mods. Another port holds
        // no power, so ports never pass it to each other.
        if (entity instanceof MachineBlockEntity machine) return machine.energy();
        if (entity instanceof ArchiveBlockEntity archive) return archive.energy();
        var cache = energyCaps[side.ordinal()];
        if (cache == null) {
            cache = BlockCapabilityCache.create(Capabilities.Energy.BLOCK, serverLevel, targetPos, side.getOpposite());
            energyCaps[side.ordinal()] = cache;
        }
        return cache.getCapability();
    }

    /** Whether the Power Upgrade passes power on at all: it is in, and the network is neither blocked nor over its limit. */
    boolean passesPower() {
        return level != null && !isRemoved() && !networkBlocked() && !stopped() && hasPowerUpgrade();
    }

    /** A port holds no power, so it passes none on to the network blocks it touches. */
    @Override
    public boolean carriesPower() {
        return false;
    }

    /** A source touching the port hands power straight on to its machines. */
    @Override
    public EnergyHandler powerIn() {
        return power.whole();
    }

    /** The cables hand power to each machine side on its own, so short supply is split evenly between them. */
    @Override
    public List<? extends EnergyHandler> powerInlets() {
        List<EnergyHandler> found = inlets;
        if (found == null) {
            inlets = found = List.copyOf(power.inlets());
        }
        return found;
    }

    /** FE a tick the Power Upgrade passed on to the machines, averaged over the last second. */
    public int powerFlow() {
        return power.flow();
    }

    /**
     * Like {@link #powerFlow()}, but only the part the network's cables gave: a generator or Battery touching the port
     * hands its power straight on to the machines, outside the network.
     */
    public int networkPowerFlow() {
        return power.networkFlow();
    }

    /** A port uses no power of its own: it works unless its network is over the machine limit. */
    @Override
    public boolean running() {
        return !stopped();
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
        return 0;
    }

    public boolean installed() {
        return !isRemoved() && level != null && level.getBlockEntity(worldPosition) == this;
    }

    // --- the machines ---

    /** The sides with a machine of the served kind touching them, in a fixed order. */
    public List<Direction> machineSides() {
        List<Direction> sides = new ArrayList<>();
        if (level != null) {
            for (Direction side : SIDES) {
                if (hasMachine(side)) {
                    sides.add(side);
                }
            }
        }
        return sides;
    }

    /**
     * The sides a job may send to: those with a machine of the served kind, and those facing a block of it that is a JASM
     * machine whose grid keeps that face shut, so a job can say what is wrong there.
     */
    List<Direction> spotSides() {
        List<Direction> sides = new ArrayList<>();
        settleType(false);
        for (Direction side : passive ? new Direction[0] : SIDES) {
            if (picked(blockAt(side)) && detects(side) && !takenElsewhere(side)) {
                sides.add(side);
            }
        }
        return sides;
    }

    /** Updates the port's shape to what touches each side now: a machine (idle or holding a job's items), the network, or nothing. */
    public void refreshSides() {
        if (!(level instanceof ServerLevel) || !(getBlockState().getBlock() instanceof AccessPortBlock)) {
            return;
        }
        settleType(false);
        BlockState state = getBlockState();
        BlockState shown = state;
        for (Direction side : SIDES) {
            shown = shown.setValue(AccessPortBlock.SIDES.get(side), sideLooks(side));
        }
        if (shown != state) {
            level.setBlock(worldPosition, shown, Block.UPDATE_CLIENTS);
        }
    }

    // a full port shows a pad on every block it serves
    protected boolean shownAsMachine(Direction side) {
        return hasMachine(side);
    }

    private PortSide sideLooks(Direction side) {
        BlockPos pos = worldPosition.relative(side);
        boolean network = level.getBlockState(pos).getBlock() instanceof DataCableBlock
                || level.getBlockEntity(pos) instanceof MachineBlockEntity || level.getBlockEntity(pos) instanceof ArchiveBlockEntity;
        if (passive) {
            // pipes may use every face of a Passive port: each shows a chute, in use while it holds a set; a cable keeps its neck
            return network ? PortSide.LINK : locks.isEmpty() ? PortSide.MACHINE : PortSide.BUSY;
        }
        if (shownAsMachine(side)) {
            return locks.containsKey(side) ? PortSide.BUSY : PortSide.MACHINE;
        }
        // A machine of a kind the port doesn't serve gets no neck, even a JASM one that is also on the network:
        // the neck reaches into the block and reads as a chute.
        if (servesRecipes() && detects(side)) {
            return PortSide.NONE;
        }
        BlockState there = level.getBlockState(pos);
        boolean hopperIn = there.getBlock() instanceof HopperBlock
                && there.getValue(HopperBlock.FACING) == side.getOpposite();
        return hopperIn || network ? PortSide.LINK : PortSide.NONE;
    }

    /**
     * Whether a machine of the served kind touches {@code side}: a block that takes items (or fluids or materials), but
     * not a hopper pointing into the port (that one brings results back). A block that takes nothing through the face
     * the port touches counts too if it takes something through another face, since a card may send its ingredients
     * there. A block of any other kind next to the port is no machine to it.
     */
    public boolean hasMachine(Direction side) {
        settleType(false);
        return !passive && picked(blockAt(side)) && touches(side) && !takenElsewhere(side);
    }

    /** Whether {@code block} is one of the kinds the port serves. */
    private boolean picked(@Nullable Block block) {
        return block != null && machineTypes.contains(block);
    }

    // --- one port per machine ---

    /** A port going for the machine on one of its sides, and which side that is. */
    private record Rival(AccessPortBlockEntity port, Direction side) {
        /**
         * Whether this port keeps the machine over {@code other} (on its {@code otherSide}): the one with a job's sets in the
         * machine, else the one that has been after it longest, else the lower position, so every port reaches the same answer.
         */
        boolean keepsOver(AccessPortBlockEntity other, Direction otherSide) {
            boolean busy = port.locks.containsKey(side);
            boolean otherBusy = other.locks.containsKey(otherSide);
            if (busy != otherBusy) {
                return busy;
            }
            long since = port.wantedSince[side.ordinal()];
            long otherSince = other.wantedSince[otherSide.ordinal()];
            if (since != otherSince && (since == 0 || otherSince == 0)) {
                // a port that has not looked yet comes last
                return since != 0;
            }
            if (since != otherSince) {
                return since < otherSince;
            }
            int byPosition = Long.compare(port.worldPosition.asLong(), other.worldPosition.asLong());
            return byPosition != 0 ? byPosition < 0 : side.ordinal() < otherSide.ordinal();
        }
    }

    /**
     * Whether another port keeps the machine on {@code side}, so this port does nothing with it: no recipes, no sets, no
     * pushes taken in, no power passed on. The port that had the machine first keeps it; when that port is broken or
     * turned away, the next one takes over at its next check.
     */
    public boolean takenElsewhere(@Nullable Direction side) {
        if (side == null || !(level instanceof ServerLevel) || !servesRecipes()) {
            return false;
        }
        if (claimsStale) {
            settleClaims();
        }
        return (takenSides & 1 << side.ordinal()) != 0;
    }

    /** Whether another port keeps a machine this port is after. */
    public boolean anyTaken() {
        if (!(level instanceof ServerLevel) || !servesRecipes()) {
            return false;
        }
        if (claimsStale) {
            settleClaims();
        }
        return takenSides != 0;
    }

    /** Whether the block on {@code side} is a machine of the served kind that the port is after. */
    private boolean wants(Direction side) {
        settleType(false);
        return servesRecipes() && !passive && picked(blockAt(side)) && detects(side);
    }

    /** The other ports going for the machine on {@code side}: block ports beside it, and thin ports on cables beside it. */
    private List<Rival> rivals(Direction side) {
        BlockPos machine = worldPosition.relative(side);
        List<Rival> found = new ArrayList<>(2);
        for (Direction around : SIDES) {
            BlockPos there = machine.relative(around);
            if (!level.isLoaded(there)) {
                continue;
            }
            Direction toward = around.getOpposite();
            AccessPortBlockEntity other = switch (level.getBlockEntity(there)) {
                case DataCableBlockEntity cable -> cable.port(toward);
                case AccessPortBlockEntity full -> full;
                case null, default -> null;
            };
            if (other != null && other != this && !other.isRemoved() && other.wants(toward)) {
                found.add(new Rival(other, toward));
            }
        }
        return found;
    }

    /**
     * Works out which machines this port keeps. A port notes when it first went for a machine and forgets it once it no
     * longer does (broken, turned away, the machine replaced by another kind), and of the ports at one machine the one
     * that has been there longest keeps it. A machine in an unloaded spot stays as it was.
     */
    private void settleClaims() {
        claimsStale = false;
        if (!(level instanceof ServerLevel) || !servesRecipes()) {
            return;
        }
        int taken = 0;
        boolean changed = false;
        for (Direction side : SIDES) {
            int bit = 1 << side.ordinal();
            if (face() != null && side != face()) {
                continue;
            }
            if (blockAt(side) == null) {
                taken |= takenSides & bit;
                continue;
            }
            if (!wants(side)) {
                if (wantedSince[side.ordinal()] != 0) {
                    wantedSince[side.ordinal()] = 0;
                    changed = true;
                }
                continue;
            }
            List<Rival> rivals = rivals(side);
            if (wantedSince[side.ordinal()] == 0) {
                // after every port already there, even one that came in the same tick
                long since = level.getGameTime();
                for (Rival rival : rivals) {
                    since = Math.max(since, rival.port().wantedSince[rival.side().ordinal()] + 1);
                }
                wantedSince[side.ordinal()] = since;
                changed = true;
            }
            for (Rival rival : rivals) {
                if (rival.keepsOver(this, side)) {
                    taken |= bit;
                    break;
                }
            }
        }
        if (taken != takenSides) {
            takenSides = taken;
            changed = true;
        }
        if (changed) {
            setChanged();
        }
    }

    /** The block on {@code side} if the port looks that way and it is loaded; a thin port looks only at its face. */
    private @Nullable Block blockAt(Direction side) {
        if (level == null || face() != null && side != face()) {
            return null;
        }
        BlockPos pos = worldPosition.relative(side);
        return level.isLoaded(pos) ? level.getBlockState(pos).getBlock() : null;
    }

    /** Whether a block that takes items, fluids or materials touches {@code side}, whatever its kind. */
    private boolean touches(Direction side) {
        if (level == null) {
            return false;
        }
        BlockPos pos = worldPosition.relative(side);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState there = level.getBlockState(pos);
        if (there.isAir() || there.getBlock() instanceof HopperBlock
                && there.getValue(HopperBlock.FACING) == side.getOpposite()) {
            return false;
        }
        return Machines.inlet(level, pos, side.getOpposite()) != null || Machines.fluidInlet(level, pos, side.getOpposite()) != null
                || !Machines.materialInlets(level, pos, side.getOpposite()).isEmpty() || Machines.takesAnywhere(level, pos);
    }

    /** Whether the block on {@code side} can be a machine to the port: one that takes items now, or a JASM machine whose grid shuts that face. */
    private boolean detects(Direction side) {
        return touches(side) || Machines.shutGrid(level, worldPosition.relative(side));
    }

    /** The kinds of machine touching the port, in the order of its sides. */
    public List<Block> machineTypesInRange() {
        List<Block> types = new ArrayList<>(2);
        if (level == null || !servesRecipes()) {
            return types;
        }
        for (Direction side : SIDES) {
            Block block = blockAt(side);
            if (block != null && !types.contains(block) && detects(side)) {
                types.add(block);
            }
        }
        return types;
    }

    /**
     * The first kind of machine the port serves, after looking at what touches it now; null with none set. The port keeps
     * its kinds while a machine of one of them touches the port, takes the first machine's kind when none does, and keeps
     * its last kinds while no machine touches at all.
     */
    public @Nullable Block servedType() {
        settleType(false);
        return machineType();
    }

    /** Every kind of machine the port serves, in the order they were picked, after looking at what touches it now. */
    public List<Block> servedTypes() {
        settleType(false);
        return List.copyOf(machineTypes);
    }

    /**
     * Makes the port serve only this kind of machine, from those touching it now.
     *
     * @return false if no machine of that kind touches the port
     */
    public boolean chooseMachineType(Block type) {
        if (!machineTypesInRange().contains(type)) {
            return false;
        }
        setMachineType(type);
        refreshSides();
        return true;
    }

    /**
     * The machine picker's click: a kind the port doesn't serve yet is added, one it serves is taken off. The last kind it
     * serves among those touching it stays, so the port always has a machine. Kinds picked earlier that touch it no longer
     * are kept. Refused while Passive.
     *
     * @return false if refused or nothing changed
     */
    public boolean toggleMachineType(Block type) {
        List<Block> inRange = machineTypesInRange();
        if (passive || !inRange.contains(type)) {
            return false;
        }
        if (machineTypes.contains(type)) {
            if (inRange.stream().filter(machineTypes::contains).count() <= 1) {
                return false;
            }
            machineTypes.remove(type);
        } else {
            machineTypes.add(type);
        }
        // claims are settled side by side: a side the port no longer wants is let go, a new one waits its turn
        claimsStale = true;
        setChanged();
        refreshSides();
        return true;
    }

    /**
     * Keeps the served kind pointing at something that touches the port. Nothing is done unless a side's block changed
     * since the last look, or {@code force} is set. With a kind set and a machine of it still touching, nothing changes
     * (with two kinds there, the old one stays). With none of it touching, the first machine's kind is taken, unless a
     * side is in an unloaded chunk, where the old machine may still be.
     */
    private void settleType(boolean force) {
        if (!(level instanceof ServerLevel) || !servesRecipes()) {
            return;
        }
        boolean changed = force;
        boolean unsure = false;
        for (Direction side : SIDES) {
            Block now = blockAt(side);
            if (now != around[side.ordinal()]) {
                around[side.ordinal()] = now;
                changed = true;
            }
            unsure |= now == null && (face() == null || side == face());
        }
        if (!changed) {
            return;
        }
        claimsStale = true;
        if (passive) {
            // the picks are kept as they were for when the port is Active again
            return;
        }
        if (!machineTypes.isEmpty()) {
            for (Direction side : SIDES) {
                if (picked(around[side.ordinal()]) && detects(side)) {
                    return;
                }
            }
            if (unsure) {
                return;
            }
        }
        for (Direction side : SIDES) {
            Block block = around[side.ordinal()];
            if (block != null && detects(side)) {
                setMachineType(block);
                return;
            }
        }
    }

    /**
     * The name of the machine on {@code side}: the block's own name, or the port's name if it has one (with the
     * block's after it when the port has more than one machine).
     */
    public Component machineName(Direction side) {
        if (passive) {
            // a Passive port's sets wait in the port itself
            return getName();
        }
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
        MutableComponent names = Component.empty();
        for (int i = 0; i < sides.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(Machines.blockName(level, worldPosition.relative(sides.get(i))));
        }
        return names;
    }


    /** The face a thin port is mounted on; null for the full block, which serves every side. */
    public @Nullable Direction face() {
        return null;
    }

    /** The one side with a machine, as the port shows it, or null with none or several. */
    public @Nullable Direction onlyMachineSide() {
        if (face() != null) {
            return face();
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof AccessPortBlock)) {
            return null;
        }
        Direction only = null;
        for (Direction side : Direction.values()) {
            PortSide looks = state.getValue(AccessPortBlock.SIDES.get(side));
            if (looks == PortSide.MACHINE || looks == PortSide.BUSY) {
                if (only != null) {
                    return null;
                }
                only = side;
            }
        }
        return only;
    }

    /** Only Access Ports serve recipes and keep their settings this way; the other ports built on them serve none. */
    private boolean servesRecipes() {
        return !(this instanceof TransferPortBlockEntity);
    }

    // --- the recipes it serves ---

    /** The first kind of machine the port serves, or null when none is set yet. */
    public @Nullable Block machineType() {
        return machineTypes.isEmpty() ? null : machineTypes.iterator().next();
    }

    /** Makes {@code type} the one kind the port serves (none for null). */
    public void setMachineType(@Nullable Block type) {
        Set<Block> wanted = type == null ? Set.of() : Set.of(type);
        if (!machineTypes.equals(wanted)) {
            machineTypes.clear();
            machineTypes.addAll(wanted);
            // a new kind is a new claim: the time spent after the old machines counts for nothing
            Arrays.fill(wantedSince, 0);
            claimsStale = true;
            setChanged();
        }
    }

    // --- Active and Passive ---

    /** Whether the port is Passive: its sets wait in its hidden buffer for pipes instead of going into machines. */
    public boolean passive() {
        return passive;
    }

    /** Only the full Access Port can be Passive. */
    public boolean canBePassive() {
        return face() == null && servesRecipes();
    }

    /** Whether Active and Passive can be switched now: not while a job holds the port or its hidden buffer holds anything. */
    public boolean modeSwitchable() {
        return canBePassive() && locks.isEmpty() && heldEmpty();
    }

    /** Makes the port Passive or Active. Refused (false) while it can't be switched. */
    public boolean setPassive(boolean on) {
        if (on == passive || !modeSwitchable()) {
            return false;
        }
        passive = on;
        // Passive gives its machines up to other ports; Active goes after them again, behind any port already there
        claimsStale = true;
        Arrays.fill(wantedSince, 0);
        settleType(true);
        settleClaims();
        setChanged();
        refreshSides();
        return true;
    }

    /** The processing recipes the port serves, in the order they were picked. */
    public List<ProcessingCard> recipes() {
        return List.copyOf(recipes);
    }

    /** Whether the port serves {@code card}'s recipe. */
    public boolean serves(ProcessingCard card) {
        // most ports serve nothing: skip working out the card's hash for them
        return !recipes.isEmpty() && recipes.contains(card);
    }

    /** Starts serving {@code card}'s recipe. False if it already did, the port is full, or it serves none. */
    public boolean select(ProcessingCard card) {
        if (!servesRecipes() || recipes.size() >= MAX_RECIPES || !recipes.add(card.clean())) {
            return false;
        }
        setChanged();
        return true;
    }

    /** Serves exactly {@code cards}, each once and in that order, at most {@link #MAX_RECIPES}, in place of what it served. */
    public void replaceRecipes(List<ProcessingCard> cards) {
        if (!servesRecipes()) {
            return;
        }
        Set<ProcessingCard> now = new LinkedHashSet<>();
        for (ProcessingCard card : cards) {
            if (now.size() < MAX_RECIPES) {
                now.add(card.clean());
            }
        }
        if (!List.copyOf(now).equals(List.copyOf(recipes))) {
            recipes.clear();
            recipes.addAll(now);
            setChanged();
        }
    }

    /** Whether the port serves as many recipes as it can. */
    public boolean recipesFull() {
        return recipes.size() >= MAX_RECIPES;
    }

    /** Stops serving {@code card}'s recipe. */
    public boolean unselect(ProcessingCard card) {
        if (!recipes.remove(card)) {
            return false;
        }
        setChanged();
        return true;
    }

    /**
     * The recipes the port serves that no rack holds right now (a card taken out, or edited): kept, and served again
     * once a card with that recipe is back in a rack. {@code onNetwork} is every card in the network's racks.
     */
    public List<ProcessingCard> missingRecipes(Set<Card> onNetwork) {
        return recipes.stream().filter(card -> !onNetwork.contains(card)).toList();
    }

    /** What the port's item keeps when it is mined or wrenched. */
    public PortSettings portSettings() {
        return servesRecipes()
                ? new PortSettings(Optional.ofNullable(machineType()), List.copyOf(recipes), blockingMode, redstoneMode,
                        machineTypes.stream().skip(1).toList(), passive)
                : PortSettings.NONE;
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
        Map<ItemResource, Long> held = new LinkedHashMap<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                held.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return held;
    }

    // --- what is pushed in ---

    /**
     * Whether machines, hoppers and pipes may push into the port at all. Items wait in its buffer; fluid goes straight to
     * a job waiting for it or the network's storage, and only as much as fits.
     */
    public boolean takesPushes() {
        return true;
    }

    /** What machines, hoppers and pipes push items into through {@code side} (null when no side is given), or null. */
    public @Nullable ResourceHandler<ItemResource> itemInput(@Nullable Direction side) {
        return takesPushes() ? intake.itemInput(side) : null;
    }

    /**
     * What machines and pipes push fluid into through {@code side} (null when no side is given), or null. It takes only
     * what fits right now, within the port's allowance, and keeps none: it is stored when the push goes through.
     */
    public @Nullable ResourceHandler<FluidResource> fluidInput(@Nullable Direction side) {
        return takesPushes() ? intake.fluidInput(side) : null;
    }

    /** Whether the port lets {@code fluid} in when pushed. */
    protected boolean acceptsPushedFluid(FluidResource fluid) {
        return true;
    }

    /**
     * How much of {@code amount} pushed through {@code side} goes to the jobs on this port waiting for it, before the
     * network's storage. With {@code simulate} nothing changes.
     */
    protected long creditPushedFluid(ServerLevel level, @Nullable Direction side, FluidResource fluid, long amount, boolean simulate) {
        return JobRunner.creditPushedFluid(level, this, side, fluid, amount, simulate);
    }

    /** Fluid a push left in the port (something else filled the room within the same push) is stored again once a second. */
    protected void tickIntake() {
        if (intake.holdsFluid() && level != null && (level.getGameTime() + worldPosition.asLong()) % 20 == 0) intake.storeHeldFluids();
    }

    /** The port is going: fluid it still holds goes to the network if there is room. */
    public void releaseHeldFluids() {
        intake.releaseHeldFluids();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel && !heldEmpty()) {
            returnHeldOnBreak(serverLevel);
        }
        super.preRemoveSideEffects(pos, state);
        releaseHeldFluids();
    }

    // --- the hidden buffer (Passive) ---

    private static boolean isHeld(int slot) {
        return slot >= HELD_START && slot < HELD_START + HELD_SLOTS;
    }

    /** Whether the hidden buffer and the hidden tank hold nothing. */
    public boolean heldEmpty() {
        for (int i = HELD_START; i < HELD_START + HELD_SLOTS; i++) {
            if (!items.get(i).isEmpty()) {
                return false;
            }
        }
        return intake.outgoingEmpty();
    }

    /** What the hidden buffer holds, by item. */
    public Map<ItemResource, Long> heldItems() {
        Map<ItemResource, Long> held = new LinkedHashMap<>();
        for (int i = HELD_START; i < HELD_START + HELD_SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                held.merge(ItemResource.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return held;
    }

    /** What the hidden tank holds, by fluid. */
    public Map<FluidResource, Long> heldFluids() {
        return intake.outgoing();
    }

    /**
     * Puts one set into the hidden buffer, all of it or nothing, as {@link Machines#push} puts one into a machine. It waits
     * for the port's allowance like a push; with Blocking Mode on it waits until no set of the job is out here; it is
     * refused while the buffer or the tank has no room for the whole set. Materials can't be held.
     */
    Machines.Push hold(List<ProcessingCard.Amount> set) {
        long shares = 0;
        Map<FluidResource, Long> fluids = new LinkedHashMap<>();
        for (ProcessingCard.Amount amount : set) {
            if (amount.isMaterial()) {
                return Machines.Push.SHUT;
            }
            if (amount.isFluid()) {
                fluids.merge(amount.fluid(), (long) amount.count(), Long::sum);
                shares += FluidAmounts.shares(amount.count());
            } else if (!amount.isEmpty()) {
                shares += amount.count();
            }
        }
        int count = (int) Math.min(shares, Integer.MAX_VALUE);
        if (!canSendBatch(count)) {
            return Machines.Push.WAIT;
        }
        if (blockingMode && !locks.isEmpty()) {
            return Machines.Push.BUSY;
        }
        List<ItemStack> after = new ArrayList<>(HELD_SLOTS);
        for (int i = HELD_START; i < HELD_START + HELD_SLOTS; i++) {
            after.add(items.get(i).copy());
        }
        for (ProcessingCard.Amount amount : set) {
            if (!amount.isFluid() && !amount.isEmpty() && !fits(after, amount.item(), amount.count())) {
                return Machines.Push.REFUSED;
            }
        }
        if (!intake.holdOutgoing(fluids, true)) {
            return Machines.Push.REFUSED;
        }
        for (int i = 0; i < HELD_SLOTS; i++) {
            items.set(HELD_START + i, after.get(i));
        }
        intake.holdOutgoing(fluids, false);
        transferred(count);
        setChanged();
        return Machines.Push.SENT;
    }

    /** Adds {@code count} of {@code item} to {@code slots}, filling matching stacks first. False if it doesn't all fit. */
    private static boolean fits(List<ItemStack> slots, ItemResource item, int count) {
        int left = count;
        for (ItemStack stack : slots) {
            if (left > 0 && !stack.isEmpty() && item.matches(stack)) {
                int n = Math.min(left, stack.getMaxStackSize() - stack.getCount());
                if (n > 0) {
                    stack.grow(n);
                    left -= n;
                }
            }
        }
        for (int i = 0; i < slots.size() && left > 0; i++) {
            if (slots.get(i).isEmpty()) {
                ItemStack put = item.toStack(Math.min(left, item.toStack(1).getMaxStackSize()));
                slots.set(i, put);
                left -= put.getCount();
            }
        }
        return left <= 0;
    }

    /**
     * Hands what the hidden buffer and tank hold to {@code items} and {@code fluids}, each taking what it can and saying how
     * much it took; the rest stays.
     */
    public void emptyHeld(ToLongBiFunction<ItemResource, Long> items, ToLongBiFunction<FluidResource, Long> fluids) {
        for (int i = HELD_START; i < HELD_START + HELD_SLOTS; i++) {
            ItemStack stack = this.items.get(i);
            if (!stack.isEmpty()) {
                long took = Math.clamp(items.applyAsLong(ItemResource.of(stack), (long) stack.getCount()), 0, stack.getCount());
                stack.shrink((int) took);
            }
        }
        intake.emptyOutgoing(fluids);
        setChanged();
    }

    /** What is held goes to the network's storage, as far as there is room and its Deck can be reached; the rest stays. */
    void returnHeldToNetwork(ServerLevel serverLevel) {
        DeckTarget target = deckTarget(serverLevel);
        if (target == null) {
            return;
        }
        var storage = DeckStorage.checked(WaferStore.get(serverLevel.getServer()), target.deck(), target.player());
        Map<ItemResource, Long> accepted = new LinkedHashMap<>(storage.depositAmounts(heldItems(), DeckStorage.Excess.KEEP));
        emptyHeld((key, n) -> {
            long took = Math.min(n, accepted.getOrDefault(key, 0L));
            accepted.merge(key, -took, Long::sum);
            return took;
        }, (fluid, n) -> storage.depositFluidResult(fluid, n, DeckStorage.Excess.KEEP).total());
        Jobs.refreshOpenDeck(target.player(), target.deck());
    }

    /**
     * The port is being broken with something held. Fluid goes back to the job holding the port (jobs keep their
     * ingredients outside the network, so this works with or without one); items too while the port is on a network.
     * Anything left goes to the network's storage, and items that still have nowhere to go drop where the port stood.
     */
    private void returnHeldOnBreak(ServerLevel serverLevel) {
        boolean onNetwork = Networks.at(serverLevel, worldPosition) != null;
        JobRunner.takeBackHeld(serverLevel, this, onNetwork);
        if (onNetwork) {
            returnHeldToNetwork(serverLevel);
        }
        for (int i = HELD_START; i < HELD_START + HELD_SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(serverLevel, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
                items.set(i, ItemStack.EMPTY);
            }
        }
        heldFluids().forEach((fluid, amount) -> Jasm.LOGGER.warn(
                "Passive port at {} was broken holding {} mB of {} with no job or network to take it", worldPosition, amount, fluid.getFluid()));
    }

    /** Items can enter the buffer even while the port is idle or stopped. */
    public boolean open() {
        return acceptsOrdinaryItems() || !locks.isEmpty() && !stopped();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= SPEED_START && slot < INVENTORY_SIZE) {
            if (stack.is(JasmItems.REDSTONE_UPGRADE.get())) return RedstoneUpgradeItem.fits(this, SPEED_START, INVENTORY_SIZE, slot, stack);
            return stack.is(JasmItems.SPEED_UPGRADE.get()) && SpeedUpgradeItem.fits(this, SPEED_START, INVENTORY_SIZE, slot, stack);
        }
        if (slot == POWER_SLOT) return stack.is(JasmItems.POWER_UPGRADE.get());
        if (slot == DECK_IN) return DeckItem.isDeck(stack);
        return slot >= 0 && slot < BUFFER_SLOTS;
    }

    /** Hoppers and pipes may only take what a Passive port holds for them. */
    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return passive && isHeld(slot);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return passive ? BUFFER_AND_HELD : BUFFER;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return slot >= 0 && slot < BUFFER_SLOTS && open() && !takenElsewhere(side);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return passive && isHeld(slot);
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
        writeOpening(buf, false);
    }

    /** The same, for a screen opened from a machine's port list, which goes back to that machine when shut. */
    public void writeRemoteOpening(RegistryFriendlyByteBuf buf) {
        writeOpening(buf, true);
    }

    private void writeOpening(RegistryFriendlyByteBuf buf, boolean fromList) {
        buf.writeBlockPos(worldPosition);
        buf.writeUtf(label, AccessPortMenu.MAX_NAME);
        AccessPortMenu.MachineView.STREAM_CODEC.apply(ByteBufCodecs.list(6))
                .encode(buf, AccessPortMenu.connectedMachines(this));
        buf.writeBoolean(fromList);
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
        output.store("redstone_mode", RedstoneMode.CODEC, redstoneMode);
        output.storeNullable("destination_deck", UUIDUtil.CODEC, destinationDeck);
        output.storeNullable("pending_linker", UUIDUtil.CODEC, pendingLinker);
        output.storeNullable("machine_type", BuiltInRegistries.BLOCK.byNameCodec(), machineType());
        if (machineTypes.size() > 1) {
            output.store("more_machine_types", BuiltInRegistries.BLOCK.byNameCodec().listOf(), machineTypes.stream().skip(1).toList());
        }
        if (passive) {
            output.putBoolean("passive", true);
        }
        if (Arrays.stream(wantedSince).anyMatch(since -> since != 0)) {
            output.store("wanted_since", Codec.LONG.listOf(), Arrays.stream(wantedSince).boxed().toList());
        }
        if (!recipes.isEmpty()) {
            output.store("recipes", ProcessingCard.CODEC.listOf(), List.copyOf(recipes));
        }
        intake.save(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(INVENTORY_SIZE + HELD_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        locks.clear();
        input.read("locks", SavedLock.CODEC.listOf()).orElse(List.of()).forEach(l -> locks.put(l.side(), new Lock(l.job(), l.step())));
        label = input.getStringOr("label", "");
        blockingMode = input.getBooleanOr("blocking_mode", false);
        redstoneMode = input.read("redstone_mode", RedstoneMode.CODEC).orElse(RedstoneMode.IGNORE);
        destinationDeck = input.read("destination_deck", UUIDUtil.CODEC).orElse(null);
        pendingLinker = input.read("pending_linker", UUIDUtil.CODEC).orElse(null);
        // older saves have one kind and no mode: Active, serving that kind
        machineTypes.clear();
        input.read("machine_type", BuiltInRegistries.BLOCK.byNameCodec()).ifPresent(machineTypes::add);
        if (!machineTypes.isEmpty()) {
            input.read("more_machine_types", BuiltInRegistries.BLOCK.byNameCodec().listOf()).orElse(List.of()).forEach(machineTypes::add);
        }
        passive = canBePassive() && input.getBooleanOr("passive", false);
        Arrays.fill(wantedSince, 0);
        List<Long> savedSince = input.read("wanted_since", Codec.LONG.listOf()).orElse(List.of());
        for (int i = 0; i < Math.min(savedSince.size(), wantedSince.length); i++) {
            wantedSince[i] = savedSince.get(i);
        }
        takenSides = 0;
        claimsStale = true;
        recipes.clear();
        input.read("recipes", ProcessingCard.CODEC.listOf()).orElse(List.of()).forEach(card -> recipes.add(card.clean()));
        intake.load(input);
    }

    /** The mined item keeps the port's name and its settings. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        // Inventory contents drop separately when either form is broken.
        components.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (!label.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        }
        PortSettings settings = portSettings();
        if (!settings.isDefault()) {
            components.set(JasmComponents.PORT_SETTINGS.get(), settings);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component name = components.get(DataComponents.CUSTOM_NAME);
        label = name == null ? "" : name.getString();
        // read only so it isn't carried along: what early test builds kept here means nothing now
        components.get(JasmComponents.PORT_MEMORY.get());
        if (servesRecipes()) {
            PortSettings settings = components.getOrDefault(JasmComponents.PORT_SETTINGS.get(), PortSettings.NONE);
            machineTypes.clear();
            machineTypes.addAll(settings.machines());
            passive = canBePassive() && settings.passive();
            recipes.clear();
            recipes.addAll(settings.recipes());
            blockingMode = settings.blocking();
            redstoneMode = settings.redstone();
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("label");
        output.discard("wanted_since");
        if (servesRecipes()) {
            output.discard("machine_type");
            output.discard("more_machine_types");
            output.discard("passive");
            output.discard("recipes");
            output.discard("blocking_mode");
            output.discard("redstone_mode");
        }
    }
}
