package dev.micolash.jasm.station;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmEntities;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Bitling Station: holds one critter and lets a living Bitling of that kind roam around it. The creature is only a
 * body; its battery is the critter item's own charge. It drains while the Bitling is out and about and fills, from the
 * station's power, while it sits on the pad. Taking the critter out removes the creature.
 */
public class BitlingStationBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOT = 0;
    public static final int SLOTS = 1;
    /** Only a landing place for power on its way into the critter's battery. */
    public static final int CAPACITY = 2_000;
    public static final int MIN_RADIUS = 4;
    /** Ticks the station waits for a Bitling it cannot find in a loaded area before it makes a new one. */
    private static final int LOST_AFTER = 100;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private int radius = -1;
    private @Nullable UUID bitlingId;
    private @Nullable BlockPos lastPos;
    private int lostTicks;
    /** Ticks left before a knocked-out Bitling comes back; 0 when none is. */
    private int knockedOut;
    private StationStatus status = StationStatus.EMPTY;
    private boolean charging;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            ItemStack critter = items.get(SLOT);
            return switch (index) {
                case BitlingStationMenu.DATA_STATUS -> status.ordinal();
                case BitlingStationMenu.DATA_KNOCKED_OUT -> (knockedOut + 19) / 20;
                case BitlingStationMenu.DATA_RADIUS -> radius();
                case BitlingStationMenu.DATA_RADIUS_MAX -> maxRadius();
                case BitlingStationMenu.DATA_ENERGY_LOW -> ContainerWords.low(BitlingItem.energy(critter));
                case BitlingStationMenu.DATA_ENERGY_HIGH -> ContainerWords.high(BitlingItem.energy(critter));
                case BitlingStationMenu.DATA_BATTERY_LOW -> ContainerWords.low(battery(critter));
                case BitlingStationMenu.DATA_BATTERY_HIGH -> ContainerWords.high(battery(critter));
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return BitlingStationMenu.DATA_COUNT;
        }
    };

    public BitlingStationBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.BITLING_STATION_ENTITY.get(), pos, state, CAPACITY);
    }

    private static int battery(ItemStack critter) {
        return critter.getItem() instanceof BitlingItem bitling ? bitling.battery() : 0;
    }

    /** The Bitling's feet when it sits on the pad. */
    public Vec3 padCentre() {
        return new Vec3(worldPosition.getX() + 0.5, worldPosition.getY() + BitlingStationBlock.PAD_HEIGHT, worldPosition.getZ() + 0.5);
    }

    // --- the tick ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, BitlingStationBlockEntity station) {
        station.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        // Asking for the network keeps it alive, so it passes power on to this block each tick.
        payForTick();
        ItemStack critter = items.get(SLOT);
        if (!(critter.getItem() instanceof BitlingItem bitling)) {
            removeBitling(level);
            knockedOut = 0;
            charging = false;
            status = StationStatus.EMPTY;
            return;
        }
        if (knockedOut > 0) {
            status = StationStatus.KNOCKED_OUT;
            if (--knockedOut == 0) {
                spawn(level, bitling);
            }
            return;
        }
        StationBitling body = bitling(level);
        if (body != null && (body.kind() != bitling.kind() || body.stage() != bitling.stage())) {
            // A different critter was put in: its body goes and a new one comes.
            body.discard();
            body = null;
            bitlingId = null;
        }
        if (body == null) {
            status = StationStatus.ROAMING;
            charging = false;
            // A Bitling in an area that isn't loaded is only frozen there; one that is nowhere is made again.
            if (bitlingId == null || lastPos != null && level.isLoaded(lastPos) && ++lostTicks >= LOST_AFTER) {
                spawn(level, bitling);
            }
            return;
        }
        lostTicks = 0;
        lastPos = body.blockPosition();
        charging = body.sittingOnPad();
        account(critter, bitling);
        status = charging && batteryFraction() < 1 && energy.getAmountAsInt() <= 0 ? StationStatus.WAITING_FOR_POWER : body.status();
    }

    /** Drains the battery while the Bitling is out, fills it from the station's power while it sits on the pad. */
    private void account(ItemStack critter, BitlingItem bitling) {
        int charge = BitlingItem.energy(critter);
        if (charging) {
            int amount = energy.getAmountAsInt();
            int moved = Math.min(amount, bitling.battery() - charge);
            if (moved > 0) {
                energy.set(amount - moved);
                critter.set(JasmComponents.ENERGY.get(), charge + moved);
                setChanged();
            }
        } else {
            int used = Math.min(charge, JasmConfig.STATION_ROAM_DRAIN.getAsInt());
            if (used > 0) {
                critter.set(JasmComponents.ENERGY.get(), charge - used);
                setChanged();
            }
        }
    }

    /** The Bitling's battery as a fraction, 0 to 1. */
    public double batteryFraction() {
        ItemStack critter = items.get(SLOT);
        int battery = battery(critter);
        return battery <= 0 ? 0 : Math.clamp(BitlingItem.energy(critter) / (double) battery, 0.0, 1.0);
    }

    public int critterEnergy() {
        return BitlingItem.energy(items.get(SLOT));
    }

    // --- the Bitling itself ---

    private @Nullable StationBitling bitling(ServerLevel level) {
        return bitlingId != null && level.getEntity(bitlingId) instanceof StationBitling found && !found.isRemoved() ? found : null;
    }

    private void spawn(ServerLevel level, BitlingItem bitling) {
        StationBitling body = JasmEntities.STATION_BITLING.get().create(level, EntitySpawnReason.TRIGGERED);
        if (body == null) {
            return;
        }
        Vec3 pad = padCentre();
        body.setup(worldPosition, bitling.kind(), bitling.stage());
        body.snapTo(pad.x, pad.y, pad.z, level.getRandom().nextFloat() * 360F, 0);
        level.addFreshEntity(body);
        level.sendParticles(ParticleTypes.POOF, pad.x, pad.y + 0.3, pad.z, 8, 0.15, 0.15, 0.15, 0.02);
        bitlingId = body.getUUID();
        lastPos = body.blockPosition();
        lostTicks = 0;
        setChanged();
    }

    private void removeBitling(ServerLevel level) {
        StationBitling body = bitling(level);
        if (body != null) {
            body.discard();
        }
        bitlingId = null;
    }

    /** Whether {@code id} is the Bitling this station keeps; any other one claiming this station is left over. */
    public boolean claims(UUID id) {
        return id.equals(bitlingId) && items.get(SLOT).getItem() instanceof BitlingItem;
    }

    /** The Bitling was knocked out: it comes back after a wait. */
    public void knockedOut() {
        bitlingId = null;
        knockedOut = JasmConfig.STATION_RESPAWN_SECONDS.getAsInt() * 20;
        charging = false;
        status = StationStatus.KNOCKED_OUT;
        setChanged();
    }

    // --- settings, for the screen ---

    public int maxRadius() {
        return Math.max(MIN_RADIUS, JasmConfig.STATION_RADIUS_MAX.getAsInt());
    }

    public int radius() {
        int value = radius < 0 ? JasmConfig.STATION_RADIUS_DEFAULT.getAsInt() : radius;
        return Math.clamp(value, MIN_RADIUS, maxRadius());
    }

    public void setRadius(int value) {
        radius = Math.clamp(value, MIN_RADIUS, maxRadius());
        setChanged();
    }

    public StationStatus status() {
        return status;
    }

    public ContainerData data() {
        return data;
    }

    public static boolean accepts(int slot, ItemStack stack) {
        return slot == SLOT && stack.getItem() instanceof BitlingItem;
    }

    /** No drain of its own: the Bitling's battery pays for everything. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    // --- container ---

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
    }

    /** Hoppers and pipes can't reach the critter slot. */
    @Override
    public int[] getSlotsForFace(Direction direction) {
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
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
        return new BitlingStationMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition),
                getBlockState().getBlock());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("radius", radius);
        output.storeNullable("bitling", UUIDUtil.CODEC, bitlingId);
        output.storeNullable("last_pos", BlockPos.CODEC, lastPos);
        output.putInt("knocked_out", knockedOut);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        radius = input.getIntOr("radius", -1);
        bitlingId = input.read("bitling", UUIDUtil.CODEC).orElse(null);
        lastPos = input.read("last_pos", BlockPos.CODEC).orElse(null);
        knockedOut = Math.max(0, input.getIntOr("knocked_out", 0));
    }
}
