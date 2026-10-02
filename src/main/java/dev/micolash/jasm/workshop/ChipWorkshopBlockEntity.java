package dev.micolash.jasm.workshop;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.core.ChipOdds;
import dev.micolash.jasm.core.ChipType;
import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The Chip Workshop: a Bitling turns Blank Chips into Logic, Memory and Link chips. The Workshop itself uses no power:
 * whatever a cable or a neighbour gives it goes straight into the critter's battery, and each chip made costs the
 * critter some of that. Left without power, the critter works until its battery runs flat, then naps until it is charged.
 */
public class ChipWorkshopBlockEntity extends MachineBlockEntity {
    public static final int INPUT = 0;
    public static final int OUTPUT_FIRST = 1;
    public static final int OUTPUT_COUNT = 15;
    public static final int CRITTER = OUTPUT_FIRST + OUTPUT_COUNT;
    public static final int SLOTS = CRITTER + 1;
    /** Only a landing place for power on its way into the critter's battery. */
    public static final int CAPACITY = 1_000;
    public static final int BATCH_SIZE = 8;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final ResourceHandler<ItemResource> automation = new Automation(VanillaContainerWrapper.of(this));
    private boolean batch;
    private boolean advancedSelected;
    private int progress;
    /** Blank Chips the running operation will turn into chips. */
    private int operationChips;
    private boolean napping;
    /** The critter seen last tick, so a different one put in starts fresh. Not saved: after loading, the one there is it. */
    private @Nullable Item lastCritter;
    /** Whether the last tick moved an operation along. Only for looks and the screen. */
    private boolean working;
    /** Whether power was coming in on the last tick. */
    private boolean fed;
    /** {@link #looks()} as last sent to players; -1 until the first tick. */
    private int sentLooks = -1;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            ItemStack critter = items.get(CRITTER);
            return switch (index) {
                case ChipWorkshopMenu.DATA_PROGRESS -> progress;
                case ChipWorkshopMenu.DATA_TICKS -> ticksForMode();
                case ChipWorkshopMenu.DATA_CRITTER_ENERGY_LOW -> BitlingItem.energy(critter) & 0xFFFF;
                case ChipWorkshopMenu.DATA_CRITTER_ENERGY_HIGH -> BitlingItem.energy(critter) >>> 16;
                case ChipWorkshopMenu.DATA_BATTERY_LOW -> battery(critter) & 0xFFFF;
                case ChipWorkshopMenu.DATA_BATTERY_HIGH -> battery(critter) >>> 16;
                case ChipWorkshopMenu.DATA_TRAINED_LOW -> BitlingItem.trained(critter) & 0xFFFF;
                case ChipWorkshopMenu.DATA_TRAINED_HIGH -> BitlingItem.trained(critter) >>> 16;
                case ChipWorkshopMenu.DATA_REQUIRED_LOW -> required(critter) & 0xFFFF;
                case ChipWorkshopMenu.DATA_REQUIRED_HIGH -> required(critter) >>> 16;
                case ChipWorkshopMenu.DATA_FLAGS -> (batch ? ChipWorkshopMenu.FLAG_BATCH : 0) | (advancedSelected ? ChipWorkshopMenu.FLAG_ADVANCED : 0)
                        | (napping ? ChipWorkshopMenu.FLAG_NAPPING : 0) | (working ? ChipWorkshopMenu.FLAG_WORKING : 0)
                        | (running() ? ChipWorkshopMenu.FLAG_POWERED : 0);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return ChipWorkshopMenu.DATA_COUNT;
        }
    };

    public ChipWorkshopBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), pos, state, CAPACITY);
    }

    private static int battery(ItemStack critter) {
        return critter.getItem() instanceof BitlingItem bitling ? bitling.battery() : 0;
    }

    private static int required(ItemStack critter) {
        return critter.getItem() instanceof BitlingItem bitling ? bitling.trainingRequired() : 0;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChipWorkshopBlockEntity workshop) {
        workshop.tick((ServerLevel) level);
        // Compared with what players were last told, since a critter can also be taken out between two ticks.
        if (workshop.looks() != workshop.sentLooks) {
            workshop.sentLooks = workshop.looks();
            workshop.lookChanged();
        }
    }

    /** The critter looks different now: tell players, and switch the status light. */
    private void lookChanged() {
        setChanged();
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = getBlockState();
        WorkshopStatus status = napping ? WorkshopStatus.NAPPING : working ? WorkshopStatus.WORKING : WorkshopStatus.IDLE;
        if (state.hasProperty(ChipWorkshopBlock.STATUS) && state.getValue(ChipWorkshopBlock.STATUS) != status) {
            level.setBlock(worldPosition, state.setValue(ChipWorkshopBlock.STATUS, status), Block.UPDATE_CLIENTS);
        } else {
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** {@link #looks()} as the player's game last heard it; only used there. */
    private int shownLooks;

    public int shownLooks() {
        return shownLooks;
    }

    // Players only get the critter's looks, not the slots.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("looks", looks());
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shownLooks = input.getIntOr("looks", 0);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        shownLooks = input.getIntOr("looks", 0);
    }

    private void tick(ServerLevel level) {
        boolean stopped = checkStopped();
        working = false;
        ItemStack critter = items.get(CRITTER);
        if (!(critter.getItem() instanceof BitlingItem bitling)) {
            napping = false;
            progress = 0;
            lastCritter = null;
            fed = false;
            return;
        }
        if (lastCritter != null && lastCritter != bitling) {
            napping = false;
            progress = 0;
        }
        lastCritter = bitling;
        feed(critter, bitling);
        if (stopped) {
            // A full network still charges the critter, but nothing is made.
            return;
        }
        int perChip = JasmConfig.BITLING_DRAIN_PER_CHIP.getAsInt();
        if (!napping && BitlingItem.energy(critter) < Math.max(1, perChip)) {
            napping = true;
            progress = 0;
        }
        if (napping) {
            // It wakes up once its battery is full again.
            if (BitlingItem.energy(critter) >= bitling.battery()) {
                napping = false;
            }
            return;
        }
        int chips = chipsThisOperation(critter, perChip);
        if (chips == 0) {
            // Every Blank Chip was taken out: the next operation starts from the beginning.
            progress = 0;
            return;
        }
        // Chips taken out part way through are simply not made; ones added wait for the next operation.
        operationChips = progress == 0 ? chips : Math.min(operationChips, chips);
        if (!roomFor(bitling, operationChips)) {
            return;
        }
        working = true;
        if (++progress >= ticksForMode()) {
            progress = 0;
            finish(level, critter, bitling, operationChips, perChip);
        }
        setChanged();
    }

    /** Moves the power the Workshop was given into the critter's battery. */
    private void feed(ItemStack critter, BitlingItem bitling) {
        int amount = energy.getAmountAsInt();
        fed = amount > 0;
        int charge = BitlingItem.energy(critter);
        int moved = Math.min(amount, bitling.battery() - charge);
        if (moved > 0) {
            energy.set(amount - moved);
            critter.set(JasmComponents.ENERGY.get(), charge + moved);
            setChanged();
        }
    }

    /** Ticks an operation takes in the mode it is set to. */
    private int ticksForMode() {
        return (batch ? JasmConfig.WORKSHOP_TICKS_PER_BATCH : JasmConfig.WORKSHOP_TICKS_PER_OPERATION).getAsInt();
    }

    /** Chips the next operation makes: one, or up to a batch, as many as there are Blank Chips and battery for. */
    private int chipsThisOperation(ItemStack critter, int perChip) {
        int chips = Math.min(batch ? BATCH_SIZE : 1, items.get(INPUT).getCount());
        return perChip <= 0 ? chips : Math.min(chips, BitlingItem.energy(critter) / perChip);
    }

    /**
     * Whether any roll of {@code chips} chips is sure to fit: each chip this critter can make needs a stack with room
     * for all of them or an empty slot of its own.
     */
    private boolean roomFor(BitlingItem bitling, int chips) {
        ChipOdds.Odds odds = ChipOdds.of(bitling.kind(), bitling.stage(), advancedSelected, BitlingItem.balance());
        int empty = 0;
        for (int slot = OUTPUT_FIRST; slot < CRITTER; slot++) {
            if (items.get(slot).isEmpty()) {
                empty++;
            }
        }
        int needed = 0;
        for (ChipType type : ChipType.values()) {
            double chance = switch (type) {
                case LOGIC -> odds.logic();
                case MEMORY -> odds.memory();
                case LINK -> odds.link();
            };
            if (chance <= 0) {
                continue;
            }
            if (odds.advanced() < 1 && !hasRoom(JasmItems.chip(type, false), chips)) {
                needed++;
            }
            if (odds.advanced() > 0 && !hasRoom(JasmItems.chip(type, true), chips)) {
                needed++;
            }
        }
        return needed <= empty;
    }

    private boolean hasRoom(Item chip, int count) {
        for (int slot = OUTPUT_FIRST; slot < CRITTER; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(chip) && stack.getMaxStackSize() - stack.getCount() >= count) {
                return true;
            }
        }
        return false;
    }

    private void finish(ServerLevel level, ItemStack critter, BitlingItem bitling, int chips, int perChip) {
        ChipOdds.Odds odds = ChipOdds.of(bitling.kind(), bitling.stage(), advancedSelected, BitlingItem.balance());
        Map<Item, Integer> made = new LinkedHashMap<>();
        for (int i = 0; i < chips; i++) {
            ChipOdds.Roll roll = ChipOdds.roll(odds, level.getRandom()::nextLong);
            made.merge(JasmItems.chip(roll.type(), roll.advanced()), 1, Integer::sum);
        }
        made.forEach(this::output);
        items.get(INPUT).shrink(chips);
        critter.set(JasmComponents.ENERGY.get(), BitlingItem.energy(critter) - chips * perChip);
        int required = bitling.trainingRequired();
        if (required > 0) {
            critter.set(JasmComponents.TRAINING.get(), Training.add(BitlingItem.trained(critter), chips, required));
        }
    }

    /** Puts chips into the output grid, onto matching stacks first. {@link #roomFor} made sure they fit. */
    private void output(Item chip, int count) {
        for (int slot = OUTPUT_FIRST; slot < CRITTER && count > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(chip)) {
                int moved = Math.min(count, stack.getMaxStackSize() - stack.getCount());
                stack.grow(moved);
                count -= moved;
            }
        }
        for (int slot = OUTPUT_FIRST; slot < CRITTER && count > 0; slot++) {
            if (items.get(slot).isEmpty()) {
                int moved = Math.min(count, chip.getDefaultMaxStackSize());
                items.set(slot, new ItemStack(chip, moved));
                count -= moved;
            }
        }
    }

    /** The Workshop uses none itself: the critter's battery pays. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    /**
     * Only a napping critter with no power coming in, or a full network, stops it; otherwise the Workshop runs off the
     * critter's battery.
     */
    @Override
    public boolean running() {
        return !stopped() && (!napping || fed);
    }

    // --- settings, for the screen's buttons ---

    public boolean batch() {
        return batch;
    }

    public void toggleBatch() {
        batch = !batch;
        setChanged();
    }

    public boolean advancedSelected() {
        return advancedSelected;
    }

    /** Only means something with a Byteling in the slot, so it only switches then. */
    public void toggleAdvanced() {
        if (items.get(CRITTER).getItem() instanceof BitlingItem bitling && bitling.stage() == BitlingStage.BYTELING) {
            advancedSelected = !advancedSelected;
            setChanged();
        }
    }

    public boolean napping() {
        return napping;
    }

    public int progress() {
        return progress;
    }

    /** The critter in the slot, or null. */
    public @Nullable BitlingItem critter() {
        return items.get(CRITTER).getItem() instanceof BitlingItem bitling ? bitling : null;
    }

    public ItemStack critterStack() {
        return items.get(CRITTER);
    }

    /**
     * What players need to draw the critter: kind (2 bits), stage (2 bits), napping, working and present (1 bit
     * each), from the lowest bit up.
     */
    public int looks() {
        BitlingItem critter = critter();
        if (critter == null) {
            return 0;
        }
        return critter.kind().ordinal() | critter.stage().ordinal() << 2 | (napping ? 1 << 4 : 0) | (working ? 1 << 5 : 0) | 1 << 6;
    }

    public ContainerData data() {
        return data;
    }

    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    public static boolean accepts(int slot, ItemStack stack) {
        return switch (slot) {
            case INPUT -> stack.is(JasmItems.BLANK_CHIP.get());
            case CRITTER -> stack.getItem() instanceof BitlingItem;
            default -> false;
        };
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
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
        return SLOTS;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ChipWorkshopMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition), getBlockState().getBlock());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putBoolean("batch", batch);
        output.putBoolean("advanced", advancedSelected);
        output.putInt("progress", progress);
        output.putInt("operation_chips", operationChips);
        output.putBoolean("napping", napping);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        batch = input.getBooleanOr("batch", false);
        advancedSelected = input.getBooleanOr("advanced", false);
        progress = Math.max(0, input.getIntOr("progress", 0));
        operationChips = Math.max(0, input.getIntOr("operation_chips", 0));
        napping = input.getBooleanOr("napping", false);
    }

    /** Hoppers and pipes: Blank Chips in, finished chips out. The critter slot is for players only. */
    private static final class Automation extends DelegatingResourceHandler<ItemResource> {
        Automation(ResourceHandler<ItemResource> slots) {
            super(slots);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index == INPUT && accepts(INPUT, resource.toStack(1)) ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return insert(INPUT, resource, amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index >= OUTPUT_FIRST && index < CRITTER ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            int taken = 0;
            for (int slot = OUTPUT_FIRST; slot < CRITTER && taken < amount; slot++) {
                taken += extract(slot, resource, amount - taken, transaction);
            }
            return taken;
        }
    }
}
