package dev.micolash.jasm.relay;

import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The lower half of a Relay Chatterbox: its frequency, its Link Byteling and its Dimension Upgrade, and its place in
 * the pairing. The upper half holds nothing. Mined, it keeps all three inside its item; with an item inside, it drops
 * itself however it is removed, once, like the Docking Station. Hoppers and pipes see no slots.
 */
public class RelayChatterboxBlockEntity extends MachineBlockEntity implements RelayEnd, WorldlyContainer {
    public static final int OPERATOR = 0;
    public static final int UPGRADE = 1;
    private static final int[] NO_SLOTS = new int[0];

    private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    private int frequency = NO_FREQUENCY;
    /** Set once the block, being removed, has dropped itself with its items inside, so the loot table drops nothing more. */
    private boolean droppedItself;
    /** Where the pairing last said this end stands; worked out again after the pairing says it changed. Server only. */
    private @Nullable RelayStatus status;

    public RelayChatterboxBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.RELAY_CHATTERBOX_ENTITY.get(), pos, state, 0);
    }

    /** The operator slot takes only a Link Byteling, the upgrade slot only a Dimension Upgrade. */
    public static boolean accepts(int slot, ItemStack stack) {
        return slot == OPERATOR ? stack.is(JasmItems.bitling(BitlingKind.LINK, BitlingStage.BYTELING))
                : slot == UPGRADE && stack.is(JasmItems.DIMENSION_UPGRADE.get());
    }

    public ItemStack operator() {
        return items.get(OPERATOR);
    }

    public ItemStack upgrade() {
        return items.get(UPGRADE);
    }

    // --- the pairing ---

    @Override
    public int frequency() {
        return frequency;
    }

    @Override
    public boolean hasDimensionUpgrade() {
        return upgrade().is(JasmItems.DIMENSION_UPGRADE.get());
    }

    /** A seated Link Byteling with any charge in its battery. */
    @Override
    public boolean canRun() {
        ItemStack operator = operator();
        return accepts(OPERATOR, operator) && BitlingItem.energy(operator) > 0;
    }

    /** Sets the frequency: 0 to {@link #MAX_FREQUENCY}, or {@link #NO_FREQUENCY} to clear it. Anything else is ignored. */
    public void setFrequency(int to) {
        if (to != NO_FREQUENCY && (to < 0 || to > MAX_FREQUENCY) || to == frequency) {
            return;
        }
        frequency = to;
        setChanged();
        tellPairing();
    }

    /** Where this end stands, as the pairing sees it. Server only. */
    public RelayStatus status() {
        if (status == null) {
            status = RelayLinks.status(this);
        }
        return status;
    }

    @Override
    public void pairingChanged() {
        status = null;
    }

    /** Called after the frequency, owner or either slot changed. */
    private void tellPairing() {
        if (level instanceof ServerLevel && !isRemoved()) {
            RelayLinks.changed(this);
        }
    }

    @Override
    protected void onOwnerChanged(@Nullable UUID previous) {
        super.onOwnerChanged(previous);
        tellPairing();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            RelayLinks.loaded(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel) {
            RelayLinks.unloaded(this);
        }
    }

    /**
     * Removed however (mined, in creative, by a blast, a command or another mod): with an item inside, the block drops
     * itself with everything inside, once, and stays where it fell until picked up; the loot table then adds nothing.
     * Either way its place on the frequency goes to the next in line.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide() && holdsItems()) {
            ItemStack self = new ItemStack(state.getBlock());
            self.applyComponents(collectComponents());
            ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, self);
            dropped.setUnlimitedLifetime();
            dropped.setInvulnerable(true);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
            droppedItself = true;
        }
        items.set(OPERATOR, ItemStack.EMPTY);
        items.set(UPGRADE, ItemStack.EMPTY);
        if (level instanceof ServerLevel) {
            RelayLinks.destroyed(this);
        }
    }

    private boolean holdsItems() {
        return !operator().isEmpty() || !upgrade().isEmpty();
    }

    /** Whether this block drops itself (holding an item), so its loot table must drop nothing. */
    public boolean dropsItself() {
        return droppedItself || holdsItems();
    }

    // --- the machine ---

    /** Nothing to pay for yet. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new RelayChatterboxMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    // --- the container: one item a slot, only the right one, and nothing through the faces ---

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        tellPairing();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack taken = super.removeItem(slot, amount);
        if (!taken.isEmpty()) tellPairing();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack taken = super.removeItemNoUpdate(slot);
        if (!taken.isEmpty()) tellPairing();
        return taken;
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
        return 2;
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("frequency", frequency);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(2, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        frequency = valid(input.getIntOr("frequency", NO_FREQUENCY));
    }

    private static int valid(int frequency) {
        return frequency >= 0 && frequency <= MAX_FREQUENCY ? frequency : NO_FREQUENCY;
    }

    /**
     * The mined block keeps its frequency and both items. They are never listed as container contents, so nothing that
     * opens containers in an inventory can reach them.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (frequency != NO_FREQUENCY || holdsItems()) {
            components.set(JasmComponents.RELAY_CONTENTS.get(), new RelayContents(frequency, operator().copy(), upgrade().copy()));
        }
    }

    /** Placed again: the frequency and items go back in as they were. The block tells the pairing once it is placed. */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        RelayContents held = components.get(JasmComponents.RELAY_CONTENTS.get());
        if (held != null) {
            frequency = valid(held.frequency());
            items.set(OPERATOR, accepts(OPERATOR, held.operator()) ? held.operator().copyWithCount(1) : ItemStack.EMPTY);
            items.set(UPGRADE, accepts(UPGRADE, held.upgrade()) ? held.upgrade().copyWithCount(1) : ItemStack.EMPTY);
            droppedItself = false;
            setChanged();
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("Items");
        output.discard("frequency");
    }
}
