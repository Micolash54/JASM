package dev.micolash.jasm.battery;

import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Battery's menu: no slots, only readings of the whole battery the clicked block belongs to. Synced numbers travel as
 * 16-bit words, so each reading goes as four of them.
 */
public class BatteryMenu extends AbstractContainerMenu {
    private static final int WORDS = 4;
    private static final int STORED = 0;
    private static final int CAPACITY = 1;
    private static final int IN = 2;
    private static final int OUT = 3;
    private static final int BLOCKS = 4;
    static final int DATA_COUNT = 5 * WORDS;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable BatteryBlockEntity battery;

    /** Server side. */
    public BatteryMenu(int containerId, BatteryBlockEntity battery, ContainerLevelAccess access) {
        super(JasmMenus.BATTERY.get(), containerId);
        this.battery = battery;
        this.access = access;
        this.data = new Readings(battery);
        addDataSlots(data);
    }

    /** Client side. */
    public BatteryMenu(int containerId, Inventory inventory) {
        super(JasmMenus.BATTERY.get(), containerId);
        this.battery = null;
        this.access = ContainerLevelAccess.NULL;
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
    }

    private long read(int reading) {
        long value = 0;
        for (int word = WORDS - 1; word >= 0; word--) {
            value = value << 16 | data.get(reading * WORDS + word) & 0xFFFF;
        }
        return value;
    }

    /** FE the whole battery holds. */
    public long stored() {
        return read(STORED);
    }

    public long capacity() {
        return read(CAPACITY);
    }

    /** FE a tick going in and coming out, averaged over the last second. */
    public long in() {
        return read(IN);
    }

    public long out() {
        return read(OUT);
    }

    /** Blocks sharing the battery. */
    public long blocks() {
        return read(BLOCKS);
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) == battery
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Read from the battery's last tick, so a synced screen costs no extra work. */
    private static final class Readings implements ContainerData {
        private final BatteryBlockEntity battery;

        Readings(BatteryBlockEntity battery) {
            this.battery = battery;
        }

        @Override
        public int get(int index) {
            if (battery.isRemoved() || !(battery.getLevel() instanceof ServerLevel level)) {
                return 0;
            }
            BatteryGroup group = battery.group(level);
            long value = switch (index / WORDS) {
                case STORED -> group.stored();
                case CAPACITY -> group.energy().getCapacityAsLong();
                case IN -> group.flowIn();
                case OUT -> group.flowOut();
                default -> group.size();
            };
            return (int) (value >>> 16 * (index % WORDS)) & 0xFFFF;
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
