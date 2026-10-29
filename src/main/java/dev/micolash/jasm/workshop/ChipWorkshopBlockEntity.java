package dev.micolash.jasm.workshop;

import dev.micolash.jasm.bay.BayClock;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.core.ChipOdds;
import dev.micolash.jasm.core.ChipType;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.MachineSides;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmRecipes;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.transfer.RedstoneUpgradeItem;
import dev.micolash.jasm.transfer.SpeedUpgradeItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
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
 * whatever a cable or a neighbour gives it goes straight into the critter's battery, as fast as it comes, and each chip
 * is paid for when its job ends. A job starts once the battery holds one chip's cost; at the end it makes as many chips
 * as the battery can pay for then.
 */
public class ChipWorkshopBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    /** The 2x2 grid: Blank Chips for chips, or a Workshop recipe's ingredients. */
    public static final int GRID_FIRST = 0;
    public static final int GRID_SIZE = 4;
    /** The first grid slot. Older Workshops kept their Blank Chips in a single slot here. */
    public static final int INPUT = GRID_FIRST;
    public static final int OUTPUT_FIRST = GRID_FIRST + GRID_SIZE;
    public static final int OUTPUT_COUNT = 6;
    public static final int CRITTER = OUTPUT_FIRST + OUTPUT_COUNT;
    /** Older Workshops end at the critter, so these load empty. */
    public static final int UPGRADE_START = CRITTER + 1;
    public static final int UPGRADES = 5;
    public static final int SLOTS = UPGRADE_START + UPGRADES;
    /** What a hopper may look at: everything but the upgrade slots. */
    private static final int[] HOPPER_SLOTS = IntStream.range(0, UPGRADE_START).toArray();
    /**
     * Only a landing place for power on its way into the critter's battery: it takes this much, or as much as the
     * battery has room for if that is more.
     */
    public static final int CAPACITY = 1_000;
    public static final int BATCH_SIZE = 8;
    /** A single chip takes this share of a batch's time. */
    private static final int SINGLE_SHARE = 6;
    /** Layout 1 (no number saved) had 15 outputs, layout 2 had 6; both had a single input slot before the outputs. */
    private static final int LAYOUT = 3;
    private static final int LEGACY_OUTPUTS = 15;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** Chips from an old save that no longer fit the output; dropped at the Workshop on the next tick. */
    private final List<ItemStack> overflow = new ArrayList<>();
    private final ResourceHandler<ItemResource> automation = new Automation(VanillaContainerWrapper.of(this));
    private final MachineSides.Gated<ItemResource> itemGates = new MachineSides.Gated<>(automation);
    private final MachineSides sides = new MachineSides(MachineSides.ALL, false, this::sidesChanged);
    private boolean batch;
    private boolean advancedSelected;
    private int progress;
    private boolean napping;
    /** The critter seen last tick, so a different one put in starts fresh. Not saved: after loading, the one there is it. */
    private @Nullable Item lastCritter;
    /** Whether the last tick moved an operation along. Only for looks and the screen. */
    private boolean working;
    /** Whether power was coming in on the last tick. */
    private boolean fed;
    /** The recipe the grid and critter make, looked up again only when the grid's items or the critter change. */
    private @Nullable RecipeHolder<WorkshopRecipe> recipe;
    /** A recipe whose parts are all in the grid but which this critter can't make. */
    private @Nullable WorkshopRecipe blocked;
    private final @Nullable Item[] seenGrid = new Item[GRID_SIZE];
    private @Nullable Item seenCritter;
    private boolean lookedUp;
    /** What the progress belongs to: a recipe's id, or empty for chips. Saved, so a reload keeps a long recipe's progress. */
    private String progressFor = "";
    /** See {@link WorkshopNeed}. */
    private int need;
    /** Speed Upgrades in the slots, counted at the start of each tick. */
    private int upgrades;
    /** What a Redstone Upgrade in the column does; kept while the upgrade is out, but only followed while it is in. */
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            ItemStack critter = items.get(CRITTER);
            return switch (index) {
                case ChipWorkshopMenu.DATA_PROGRESS -> progress;
                case ChipWorkshopMenu.DATA_NEED -> need;
                case ChipWorkshopMenu.DATA_TICKS -> ticksForMode();
                case ChipWorkshopMenu.DATA_CRITTER_ENERGY_LOW -> ContainerWords.low(BitlingItem.energy(critter));
                case ChipWorkshopMenu.DATA_CRITTER_ENERGY_HIGH -> ContainerWords.high(BitlingItem.energy(critter));
                case ChipWorkshopMenu.DATA_BATTERY_LOW -> ContainerWords.low(battery(critter));
                case ChipWorkshopMenu.DATA_BATTERY_HIGH -> ContainerWords.high(battery(critter));
                case ChipWorkshopMenu.DATA_TRAINED_LOW -> ContainerWords.low(BitlingItem.trained(critter));
                case ChipWorkshopMenu.DATA_TRAINED_HIGH -> ContainerWords.high(BitlingItem.trained(critter));
                case ChipWorkshopMenu.DATA_REQUIRED_LOW -> ContainerWords.low(required(critter));
                case ChipWorkshopMenu.DATA_REQUIRED_HIGH -> ContainerWords.high(required(critter));
                case ChipWorkshopMenu.DATA_SIDES -> sides.packed(MachineSides.Kind.ITEMS);
                case ChipWorkshopMenu.DATA_FLAGS ->
                    (batch ? ChipWorkshopMenu.FLAG_BATCH : 0) | (advancedSelected ? ChipWorkshopMenu.FLAG_ADVANCED : 0)
                            | (napping ? ChipWorkshopMenu.FLAG_NAPPING : 0) | (working ? ChipWorkshopMenu.FLAG_WORKING : 0)
                            | (running() ? ChipWorkshopMenu.FLAG_POWERED : 0) | (redstonePaused() ? ChipWorkshopMenu.FLAG_PAUSED : 0);
                case ChipWorkshopMenu.DATA_REDSTONE -> redstoneMode.ordinal();
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
        workshop.sizeIntake();
        workshop.pushOutputs((ServerLevel) level);
        // Compared with what players were last told, since a critter can also be taken out between two ticks.
        if (workshop.looksChanged(workshop.looks())) {
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
        upgrades = SpeedUpgradeItem.count(this, UPGRADE_START, SLOTS);
        if (!overflow.isEmpty()) {
            for (ItemStack stack : overflow) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, stack);
            }
            overflow.clear();
            setChanged();
        }
        boolean stopped = checkStopped();
        working = false;
        ItemStack critter = items.get(CRITTER);
        if (!(critter.getItem() instanceof BitlingItem bitling)) {
            napping = false;
            progress = 0;
            lastCritter = null;
            fed = false;
            recipe = null;
            blocked = null;
            lookedUp = false;
            need = WorkshopNeed.NONE;
            return;
        }
        if (lastCritter != null && lastCritter != bitling) {
            napping = false;
            progress = 0;
        }
        lastCritter = bitling;
        feed(critter, bitling);
        if (stopped || redstonePaused()) {
            // A full network or the Redstone Upgrade's mode still lets the critter charge, but nothing is made; an
            // operation part way through keeps its progress for later.
            return;
        }
        refreshRecipe(level, bitling);
        int[] slots = recipe == null ? null : recipe.value().assign(gridInput());
        if (recipe != null && slots != null) {
            need = WorkshopNeed.NONE;
            follow(recipe.id().toString());
            tickRecipe(critter, bitling, recipe.value(), slots);
            return;
        }
        if (blankChips() > 0) {
            need = WorkshopNeed.NONE;
        } else if (blocked != null) {
            need = WorkshopNeed.of(blocked);
        } else {
            need = gridInput().items().stream().allMatch(ItemStack::isEmpty) ? WorkshopNeed.NONE : WorkshopNeed.NO_RECIPE;
        }
        follow("");
        int perChip = chipPower(upgrades);
        int affordable = perChip <= 0 ? Integer.MAX_VALUE : BitlingItem.energy(critter) / perChip;
        if (affordable == 0) {
            // Not even one chip's worth: it naps, and wakes as soon as it has that much.
            if (!napping) {
                napping = true;
                progress = 0;
            }
            return;
        }
        napping = false;
        int chips = Math.min(Math.min(batch ? BATCH_SIZE : 1, blankChips()), affordable);
        if (chips == 0) {
            // Every Blank Chip was taken out: the next operation starts from the beginning.
            progress = 0;
            return;
        }
        // The count follows the Blank Chips and the battery right up to the end: chips taken out are not made, and
        // power that comes in meanwhile pays for more.
        if (!roomFor(bitling, chips)) {
            return;
        }
        working = true;
        if (++progress >= ticksForMode()) {
            progress = 0;
            finish(level, critter, bitling, chips, perChip);
        }
        setChanged();
    }

    /** FE one chip costs with the Workshop's own price list. */
    public static int chipPower(int upgrades) {
        var table = JasmConfig.WORKSHOP_ENERGY_PER_CHIP.get();
        return table.isEmpty() ? Tuning.BITLING_DRAIN_PER_CHIP : table.get(Math.clamp(upgrades, 0, table.size() - 1));
    }

    /** Ticks a chip operation takes: a batch from the Workshop's own speed table, a single chip a sixth of that, rounded. */
    public static int chipTicks(boolean batch, int upgrades) {
        int batchTicks = BayClock.ticksFor(upgrades, JasmConfig.WORKSHOP_BATCH_TICKS.get());
        return batch ? batchTicks : Math.max(1, (batchTicks + SINGLE_SHARE / 2) / SINGLE_SHARE);
    }

    /**
     * Looks the grid up again when its items or the critter changed, and every 5 seconds in case recipes were
     * reloaded. A recipe this critter can make wins; otherwise the first matching one is kept to say what is needed.
     */
    private void refreshRecipe(ServerLevel level, BitlingItem critter) {
        boolean changed = !lookedUp || seenCritter != critter || (level.getGameTime() + worldPosition.asLong()) % 100 == 0;
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = items.get(GRID_FIRST + i);
            Item item = stack.isEmpty() ? null : stack.getItem();
            if (seenGrid[i] != item) {
                seenGrid[i] = item;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        lookedUp = true;
        seenCritter = critter;
        recipe = null;
        blocked = null;
        WorkshopInput input = gridInput();
        for (RecipeHolder<WorkshopRecipe> holder : level.getServer().getRecipeManager().recipeMap().byType(JasmRecipes.WORKSHOP_TYPE.get())) {
            if (holder.value().assign(input) == null) {
                continue;
            }
            if (holder.value().accepts(critter)) {
                recipe = holder;
                blocked = null;
                return;
            }
            if (blocked == null) {
                blocked = holder.value();
            }
        }
    }

    /** Progress belongs to one thing at a time: switching between chips and a recipe, or between recipes, starts over. */
    private void follow(String key) {
        if (!progressFor.equals(key)) {
            progressFor = key;
            progress = 0;
        }
    }

    /** Ticks a recipe takes with the Speed Upgrades inside. */
    private int recipeTicks(WorkshopRecipe made) {
        return SpeedUpgradeItem.ticks(made.ticks(), upgrades);
    }

    /** FE taken from the critter on the tick that moves the recipe on: the same steps as {@link WorkshopRecipe#energyAt}, with the extra power. */
    private long recipeEnergyAt(WorkshopRecipe made) {
        long total = SpeedUpgradeItem.power(made.energy(), upgrades);
        int ticks = recipeTicks(made);
        int step = Math.min(progress, ticks - 1);
        return total * (step + 1) / ticks - total * step / ticks;
    }

    private void tickRecipe(ItemStack critter, BitlingItem bitling, WorkshopRecipe made, int[] slots) {
        long due = recipeEnergyAt(made);
        int ticks = recipeTicks(made);
        if (!napping && due > 0 && BitlingItem.energy(critter) < due) {
            // Unlike a chip, a long recipe keeps its progress through a nap.
            napping = true;
        }
        if (napping) {
            if (BitlingItem.energy(critter) >= bitling.battery()) {
                napping = false;
            }
            return;
        }
        ItemStack result = made.result();
        if (progress + 1 >= ticks && !roomFor(result)) {
            // Done but for the last step: waits for room in the output.
            return;
        }
        working = true;
        critter.set(JasmComponents.ENERGY.get(), (int) (BitlingItem.energy(critter) - due));
        if (++progress >= ticks) {
            progress = 0;
            for (int slot : slots) {
                items.get(GRID_FIRST + slot).shrink(1);
            }
            output(result);
        }
        setChanged();
    }

    private boolean roomFor(ItemStack result) {
        for (int slot = OUTPUT_FIRST; slot < CRITTER; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, result)
                    && stack.getCount() + result.getCount() <= stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** Puts a recipe's result into the outputs; {@link #roomFor(ItemStack)} made sure it fits in one slot. */
    private void output(ItemStack result) {
        for (int slot = OUTPUT_FIRST; slot < CRITTER; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, result)
                    && stack.getCount() + result.getCount() <= stack.getMaxStackSize()) {
                stack.grow(result.getCount());
                return;
            }
        }
        for (int slot = OUTPUT_FIRST; slot < CRITTER; slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, result.copy());
                return;
            }
        }
    }

    public int need() {
        return need;
    }

    /** What the running recipe makes, or empty while making chips or nothing. */
    public ItemStack making() {
        return recipe != null && progressFor.equals(recipe.id().toString()) ? recipe.value().result() : ItemStack.EMPTY;
    }

    public int progressPercent() {
        return Math.min(100, progress * 100 / Math.max(1, ticksForMode()));
    }

    /**
     * Lets in as much power as the critter's battery has room for, so charging is only as quick as the supply. Set after
     * each tick, ready for what cables and generators give before the next one.
     */
    private void sizeIntake() {
        ItemStack critter = items.get(CRITTER);
        int room = critter.getItem() instanceof BitlingItem bitling ? Math.max(0, bitling.battery() - BitlingItem.energy(critter)) : 0;
        int size = Math.max(CAPACITY, room);
        if (energy.getCapacityAsInt() != size) {
            energy.resize(size);
        }
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
        if (recipe != null && progressFor.equals(recipe.id().toString())) {
            return recipeTicks(recipe.value());
        }
        return chipTicks(batch, upgrades);
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
        takeBlankChips(chips);
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

    @Override
    public MachineSides sides() {
        return sides;
    }

    @Override
    protected MachineSides.Gated<ItemResource> itemGates() {
        return itemGates;
    }

    /** Players may put anything but a critter in the grid; the critter slot takes only critters, the upgrade slots only Speed and Redstone Upgrades. */
    public static boolean accepts(int slot, ItemStack stack) {
        if (slot >= GRID_FIRST && slot < GRID_FIRST + GRID_SIZE) {
            return !(stack.getItem() instanceof BitlingItem);
        }
        if (slot >= UPGRADE_START && slot < SLOTS) {
            return stack.is(JasmItems.SPEED_UPGRADE.get()) || stack.is(JasmItems.REDSTONE_UPGRADE.get());
        }
        return slot == CRITTER && stack.getItem() instanceof BitlingItem;
    }

    /** {@link #accepts(int, ItemStack)}, with no more Speed Upgrades than the limit and one Redstone Upgrade in what {@code container} holds. */
    public static boolean accepts(Container container, int slot, ItemStack stack) {
        return accepts(slot, stack) && SpeedUpgradeItem.fits(container, UPGRADE_START, SLOTS, slot, stack)
                && RedstoneUpgradeItem.fits(container, UPGRADE_START, SLOTS, slot, stack);
    }

    public boolean hasRedstoneUpgrade() {
        return RedstoneUpgradeItem.elsewhere(this, UPGRADE_START, SLOTS, -1);
    }

    public RedstoneMode redstoneMode() {
        return redstoneMode;
    }

    public void setRedstoneMode(RedstoneMode mode) {
        redstoneMode = mode;
        setChanged();
    }

    /** A napping critter with no power coming in, or the Redstone Upgrade's mode: nothing is made. */
    @Override
    public boolean halted() {
        return !running() || redstonePaused();
    }

    /** Whether the Redstone Upgrade's mode stops the Workshop now. Only looked at with the upgrade in. */
    public boolean redstonePaused() {
        return level != null && hasRedstoneUpgrade() && !redstoneMode.allows(level.hasNeighborSignal(worldPosition));
    }

    /** Blank Chips across the grid. */
    public int blankChips() {
        int count = 0;
        for (int slot = GRID_FIRST; slot < GRID_FIRST + GRID_SIZE; slot++) {
            if (items.get(slot).is(JasmItems.BLANK_CHIP.get())) count += items.get(slot).getCount();
        }
        return count;
    }

    private void takeBlankChips(int count) {
        for (int slot = GRID_FIRST; slot < GRID_FIRST + GRID_SIZE && count > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(JasmItems.BLANK_CHIP.get())) {
                int taken = Math.min(count, stack.getCount());
                stack.shrink(taken);
                count -= taken;
            }
        }
    }

    public WorkshopInput gridInput() {
        return new WorkshopInput(List.copyOf(items.subList(GRID_FIRST, GRID_FIRST + GRID_SIZE)));
    }

    /**
     * Where a hopper may put {@code resource}: the grid slot already holding it, else the first empty one. Only Blank
     * Chips and Workshop recipe ingredients go in, and never into a second slot, so one item can't fill the grid.
     */
    private int automationSlot(ItemResource resource) {
        if (resource.isEmpty() || !feedable(resource.toStack(1))) {
            return -1;
        }
        int empty = -1;
        for (int slot = GRID_FIRST; slot < GRID_FIRST + GRID_SIZE; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                if (empty < 0) empty = slot;
            } else if (resource.matches(stack)) {
                return slot;
            }
        }
        return empty;
    }

    private boolean feedable(ItemStack stack) {
        if (stack.is(JasmItems.BLANK_CHIP.get())) {
            return true;
        }
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        for (RecipeHolder<WorkshopRecipe> holder : server.getServer().getRecipeManager().recipeMap().byType(JasmRecipes.WORKSHOP_TYPE.get())) {
            if (holder.value().ingredients().stream().anyMatch(ingredient -> ingredient.test(stack))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(this, slot, stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return HOPPER_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return slot < UPGRADE_START && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot < UPGRADE_START;
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
        return new ChipWorkshopMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition),
                getBlockState().getBlock());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("layout", LAYOUT);
        if (!overflow.isEmpty()) {
            ValueOutput.TypedOutputList<ItemStack> saved = output.list("overflow", ItemStack.OPTIONAL_CODEC);
            overflow.forEach(saved::add);
        }
        output.putBoolean("batch", batch);
        output.putBoolean("advanced", advancedSelected);
        output.putInt("progress", progress);
        output.putString("progress_for", progressFor);
        output.putBoolean("napping", napping);
        output.store("redstone_mode", RedstoneMode.CODEC, redstoneMode);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        // Power that came in after the last tick may be more than the usual landing place: keep all of it.
        energy.resize(Math.max(CAPACITY, input.getIntOr("energy", 0)));
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        overflow.clear();
        int layout = input.getIntOr("layout", 1);
        if (layout >= LAYOUT) {
            ContainerHelper.loadAllItems(input, items);
        } else {
            moveFromOldLayout(input, layout == 2 ? OUTPUT_COUNT : LEGACY_OUTPUTS);
        }
        for (ItemStack stack : input.listOrEmpty("overflow", ItemStack.OPTIONAL_CODEC)) {
            if (!stack.isEmpty()) overflow.add(stack);
        }
        batch = input.getBooleanOr("batch", false);
        advancedSelected = input.getBooleanOr("advanced", false);
        progress = Math.max(0, input.getIntOr("progress", 0));
        progressFor = input.getStringOr("progress_for", "");
        napping = input.getBooleanOr("napping", false);
        redstoneMode = input.read("redstone_mode", RedstoneMode.CODEC).orElse(RedstoneMode.IGNORE);
    }

    /** The old input goes to the first grid slot and the critter keeps its place; outputs are packed into the 6 there are now. */
    private void moveFromOldLayout(ValueInput input, int oldOutputs) {
        NonNullList<ItemStack> old = NonNullList.withSize(oldOutputs + 2, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, old);
        items.set(GRID_FIRST, old.get(0));
        items.set(CRITTER, old.get(oldOutputs + 1));
        int next = OUTPUT_FIRST;
        for (int slot = 1; slot <= oldOutputs; slot++) {
            ItemStack stack = old.get(slot);
            if (stack.isEmpty()) continue;
            while (next < CRITTER && !items.get(next).isEmpty()) next++;
            if (next < CRITTER) items.set(next, stack);
            else overflow.add(stack);
        }
    }

    /**
     * Hoppers and pipes, where the I/O grid lets them: Blank Chips and recipe ingredients in, finished items out. The
     * critter slot is for players only.
     */
    private final class Automation extends DelegatingResourceHandler<ItemResource> {
        Automation(ResourceHandler<ItemResource> slots) {
            super(slots);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index == automationSlot(resource) ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            int slot = automationSlot(resource);
            return slot < 0 ? 0 : super.insert(slot, resource, amount, transaction);
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
