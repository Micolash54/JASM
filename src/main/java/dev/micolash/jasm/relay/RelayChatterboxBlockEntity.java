package dev.micolash.jasm.relay;

import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
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
    /** FE this end's battery last paid for the link, and on which game tick; not saved. Server only. */
    private int paid;
    private long paidAt = Long.MIN_VALUE;
    /** Where the pairing last said this end stands; worked out again after the pairing says it changed. Server only. */
    private @Nullable RelayStatus status;
    /** The other end of the running link, worked out with {@link #status}; null while not linked. Server only. */
    private @Nullable NetworkNode partner;
    /** The front and the dish's partner as players' games were last told them, so they are only sent again on a change. Server only. */
    private int sentLook = UNSENT;
    private Partner sentPartner = Partner.NONE;
    private long sentPartnerPos;
    private int looksSent;
    /** The front and the dish's partner as this player's game was told them. Client only. */
    private int shownLook = look(RelayStatus.Light.OFF, NO_FREQUENCY, false);
    private Partner shownPartner = Partner.NONE;
    private long shownPartnerPos;
    /** The dish's movement and the space the renderer draws in. Client only, made when first drawn. */
    private @Nullable RelayDish dish;
    private @Nullable AABB renderBox;
    private static final int UNSENT = -1;

    /** Where the dish's partner is, as far as the dish cares: nowhere (not linked), here, or in another dimension. */
    public enum Partner { NONE, HERE, ELSEWHERE }

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
        return charge() > 0;
    }

    // --- the battery: the network tops up the Link Byteling, the Byteling pays for the link ---

    /** FE in the seated Link Byteling's battery, or 0 without one. */
    @Override
    public int charge() {
        ItemStack operator = operator();
        return accepts(OPERATOR, operator) ? BitlingItem.energy(operator) : 0;
    }

    /** Moves the power the block was given into the Link Byteling's battery, as the Chip Workshop charges its critter. */
    @Override
    public void topUp() {
        ItemStack operator = operator();
        if (!(accepts(OPERATOR, operator) && operator.getItem() instanceof BitlingItem bitling)) {
            return;
        }
        int amount = energy.getAmountAsInt();
        int charge = BitlingItem.energy(operator);
        int moved = Math.min(amount, bitling.battery() - charge);
        if (moved > 0) {
            energy.set(amount - moved);
            operator.set(JasmComponents.ENERGY.get(), charge + moved);
            batteryChanged();
        }
    }

    @Override
    public void spend(int amount) {
        int charge = charge();
        int taken = Math.min(amount, charge);
        if (taken > 0) {
            operator().set(JasmComponents.ENERGY.get(), charge - taken);
            batteryChanged();
        }
        if (level != null) {
            paid = taken;
            paidAt = level.getGameTime();
        }
    }

    /** FE a tick this end's battery is paying for the link right now: 0 when it paid nothing on the last tick. Server only. */
    public int powerUse() {
        return level != null && level.getGameTime() - paidAt <= 1 ? paid : 0;
    }

    /** The charge changes no comparator signal, so only saving needs to hear about it. */
    private void batteryChanged() {
        if (level != null) level.blockEntityChanged(worldPosition);
    }

    /**
     * Lets in as much power as the Link Byteling's battery has room for, so charging is only as quick as the supply. Set
     * after each tick, ready for what cables give before the next one.
     */
    private void sizeIntake() {
        ItemStack operator = operator();
        int room = accepts(OPERATOR, operator) && operator.getItem() instanceof BitlingItem bitling
                ? Math.max(0, bitling.battery() - BitlingItem.energy(operator)) : 0;
        if (energy.getCapacityAsInt() != room) {
            energy.resize(room);
        }
    }

    /**
     * Tops up the Link Byteling. While linked, the pairing does that itself, just before the pair pays, and tells
     * itself when a battery runs flat or comes back; otherwise a battery coming back is told here.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, RelayChatterboxBlockEntity relay) {
        RelayStatus now = relay.status();
        if (now != RelayStatus.LINKED && now != RelayStatus.LINKED_ONE_SIDE) {
            boolean ran = relay.canRun();
            relay.topUp();
            if (relay.canRun() != ran) {
                relay.tellPairing();
            }
        }
        relay.sizeIntake();
        relay.showLook();
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
            partner = RelayLinks.partner(this);
        }
        return status;
    }

    /** Where the other end of the running link stands, as the dish cares. Server only. */
    private Partner partner() {
        status();
        return partner == null || level == null ? Partner.NONE : partner.dimension().equals(level.dimension()) ? Partner.HERE : Partner.ELSEWHERE;
    }

    private long partnerPos() {
        return partner() == Partner.HERE && partner != null ? partner.pos().asLong() : 0;
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

    // --- what players' games are told: the console light, the number, whether the operator sits, where the dish points ---

    /** The front packed in one number: the light, whether a Link Byteling sits, and the frequency. */
    private static int look(RelayStatus.Light light, int frequency, boolean seated) {
        return light.ordinal() | (seated ? 4 : 0) | (frequency + 1) << 3;
    }

    private int look() {
        return look(status().light(), frequency, accepts(OPERATOR, operator()));
    }

    /**
     * Tells players' games when the front would be drawn differently or the dish's partner moved, and only then: never
     * for a change of charge alone.
     */
    private void showLook() {
        int now = look();
        Partner to = partner();
        long at = partnerPos();
        if (sentLook == UNSENT) {
            // nobody has been told anything yet, so nobody needs telling
            sentLook = now;
            sentPartner = to;
            sentPartnerPos = at;
        } else if ((now != sentLook || to != sentPartner || at != sentPartnerPos) && level != null) {
            sentLook = now;
            sentPartner = to;
            sentPartnerPos = at;
            looksSent++;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** How many times players' games were sent a new look of the front or a new partner for the dish. */
    public int looksSent() {
        return looksSent;
    }

    /** Where the dish's partner is, as this player's game was told. */
    public Partner shownPartner() {
        return shownPartner;
    }

    /** The partner's block, packed, when {@link #shownPartner} is {@link Partner#HERE}. */
    public long shownPartnerPos() {
        return shownPartnerPos;
    }

    /** The dish's movement, made the first time it is drawn, each block with its own rhythm. Client only. */
    public RelayDish dish() {
        if (dish == null) {
            dish = new RelayDish(worldPosition.asLong());
        }
        return dish;
    }

    /** Both halves, with room for the turning yoke's ends. Client only. */
    public AABB renderBox() {
        if (renderBox == null) {
            renderBox = new AABB(worldPosition).expandTowards(0, 1, 0).inflate(0.05, 0, 0.05);
        }
        return renderBox;
    }

    /** The console light as this player's game knows it. */
    public RelayStatus.Light shownLight() {
        return RelayStatus.Light.values()[shownLook & 3];
    }

    /** The frequency on the console as this player's game knows it, or {@link #NO_FREQUENCY}. */
    public int shownFrequency() {
        return (shownLook >> 3) - 1;
    }

    /** Whether a Link Byteling sits at the switchboard, as this player's game knows it. */
    public boolean shownSeated() {
        return (shownLook & 4) != 0;
    }

    // Players only get the look of the front, not the slots or the charge.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        int now = look();
        sentLook = now;
        sentPartner = partner();
        sentPartnerPos = partnerPos();
        tag.putInt("look", now);
        tag.putInt("partner", sentPartner.ordinal());
        if (sentPartner == Partner.HERE) {
            tag.putLong("partner_pos", sentPartnerPos);
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shownLook = input.getIntOr("look", shownLook);
        int partner = input.getIntOr("partner", 0);
        shownPartner = partner >= 0 && partner < Partner.values().length ? Partner.values()[partner] : Partner.NONE;
        shownPartnerPos = input.getLongOr("partner_pos", 0);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        handleUpdateTag(input);
    }

    // --- the machine ---

    /** The block uses none itself: the Link Byteling's battery pays for the link. */
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
        // Power that came in after the last tick may be more than the intake is sized for now: keep all of it.
        energy.resize(Math.max(0, input.getIntOr("energy", 0)));
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
