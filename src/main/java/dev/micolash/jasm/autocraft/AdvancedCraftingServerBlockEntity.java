package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * An Advanced Crafting Server: a Crafting Server with ten Processor slots and ten Storage Module slots. Players also
 * see, on its front screen, what the job makes and what is crafting at that moment, and in its info mode how far along
 * the job is.
 */
public class AdvancedCraftingServerBlockEntity extends CraftingServerBlockEntity {
    public static final int PROCESSOR_SLOTS = 10;
    public static final int MEMORY_SLOTS = 10;
    public static final int SLOTS = PROCESSOR_SLOTS + MEMORY_SLOTS;
    public static final int CAPACITY = 300_000;
    /** Ticks between checks of what is crafting right now. */
    private static final int NOW_EVERY = 5;
    /** Ticks between checks of how far along the job is. */
    private static final int PERCENT_EVERY = 20;

    /** What the front screen shows while a job runs; everyone sees the same. */
    private ServerScreenMode screenMode = ServerScreenMode.MINIMAL;

    /** What players were last told: parts and power packed by {@link #parts()}, and the two items on the screen. */
    private long sentParts = -1;
    private ItemStack sentTarget = ItemStack.EMPTY;
    private ItemStack sentNow = ItemStack.EMPTY;
    private ItemStack now = ItemStack.EMPTY;
    /** The top half's screen as last set: on, plus 2 for info; -1 until checked after loading. */
    private int sentScreen = -1;
    /** The job's details for the info screen, as last checked; null while idle or on the minimal screen. */
    private @Nullable Info info;
    /** How far along the job is and how it is doing, as last checked; -1 when not checked yet. */
    private int percent = -1;
    /** Ticks until the percent is checked again. */
    private int percentWait;
    private int running;
    private ServerScreenStatus status = ServerScreenStatus.RUNNING;

    /** The same, as a player's game last heard it. */
    private long shownParts;
    private ItemStack shownTarget = ItemStack.EMPTY;
    private ItemStack shownNow = ItemStack.EMPTY;
    private ServerScreenMode shownMode = ServerScreenMode.MINIMAL;
    private @Nullable Info shownInfo;

    /**
     * The job's details on the info screen: how many it makes (items, or mB of a fluid), how far along as a whole
     * percent, how many crafts run at once, and the colour of the arrow and percent.
     */
    public record Info(long amount, int percent, int running, ServerScreenStatus status) {}

    public AdvancedCraftingServerBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ADVANCED_CRAFTING_SERVER_ENTITY.get(), pos, state, PROCESSOR_SLOTS, MEMORY_SLOTS, CAPACITY);
    }

    /** Its screen is titled like the Crafting Server's. */
    @Override
    protected Component getDefaultName() {
        return JasmBlocks.CRAFTING_SERVER.get().getName();
    }

    @Override
    public MenuType<CraftingServerMenu> menuType() {
        return JasmMenus.ADVANCED_CRAFTING_SERVER.get();
    }

    public ServerScreenMode screenMode() {
        return screenMode;
    }

    /** Players see the change at the server's next tick. */
    public void setScreenMode(ServerScreenMode mode) {
        screenMode = mode;
        setChanged();
    }

    @Override
    protected int screenModeCode() {
        return screenMode.ordinal();
    }

    @Override
    protected void syncShown(ServerLevel level) {
        CraftingJob job = job();
        boolean on = job != null;
        boolean infoMode = screenMode == ServerScreenMode.INFO;
        int screen = (on ? 1 : 0) | (infoMode ? 2 : 0);
        if (screen != sentScreen) {
            sentScreen = screen;
            AdvancedCraftingServerBlock.setScreen(level, worldPosition, on, infoMode);
        }
        long time = level.getGameTime() + worldPosition.asLong();
        if (job == null) {
            now = ItemStack.EMPTY;
        } else if (time % NOW_EVERY == 0) {
            // The last of the crafts running now is the step being made at this moment; the final item comes first.
            List<CraftingJob.Now> rows = job.now();
            now = rows.isEmpty() ? ItemStack.EMPTY : rows.getLast().what().toStack(1);
            running = job.runningCount();
            status = ServerScreenStatus.of(job);
        }
        Info details = null;
        if (job == null || !infoMode) {
            percent = -1;
        } else {
            if (percent < 0) {
                running = job.runningCount();
                status = ServerScreenStatus.of(job);
            }
            if (percent < 0 || --percentWait <= 0) {
                percent = Math.clamp(Math.round(job.progress() * 100), 0, 100);
                percentWait = PERCENT_EVERY;
            }
            Info last = info;
            details = last != null && last.amount() == job.amount() && last.percent() == percent && last.running() == running
                    && last.status() == status ? last : new Info(job.amount(), percent, running, status);
        }
        ItemStack target = shown().getItem(0);
        long parts = parts();
        if (parts != sentParts || !ItemStack.isSameItemSameComponents(target, sentTarget) || !ItemStack.isSameItemSameComponents(now, sentNow)
                || !Objects.equals(details, info)) {
            sentParts = parts;
            sentTarget = target.copy();
            sentNow = now.copy();
            info = details;
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Three bits per slot, the part's tier plus one (0 when empty), then one bit for power. */
    private long parts() {
        long bits = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = getItem(i);
            Enum<?> tier = i < PROCESSOR_SLOTS ? ServerPartItem.processorOf(stack) : ServerPartItem.memoryOf(stack);
            if (tier != null) {
                bits |= (long) (tier.ordinal() + 1) << (3 * i);
            }
        }
        return running() ? bits | 1L << (3 * SLOTS) : bits;
    }

    @Override
    public @Nullable ProcessorTier shownProcessor(int slot) {
        int code = (int) (shownParts >> (3 * slot) & 7);
        return code == 0 ? null : ProcessorTier.values()[code - 1];
    }

    @Override
    public @Nullable MemoryTier shownModule(int slot) {
        int code = (int) (shownParts >> (3 * (PROCESSOR_SLOTS + slot)) & 7);
        return code == 0 ? null : MemoryTier.values()[code - 1];
    }

    @Override
    public boolean shownRunning() {
        return (shownParts >> (3 * SLOTS) & 1) != 0;
    }

    /** What the job makes, as a player's game knows it; empty while idle. */
    public ItemStack shownTarget() {
        return shownTarget;
    }

    /** What is crafting at this moment, as a player's game knows it; empty when nothing is. */
    public ItemStack shownNow() {
        return shownNow;
    }

    /** The front screen's mode, as a player's game knows it. */
    public ServerScreenMode shownMode() {
        return shownMode;
    }

    /** The job's details for the info screen, as a player's game knows them; null while idle or on the minimal screen. */
    public @Nullable Info shownInfo() {
        return shownInfo;
    }

    /** Crafts the installed Processors run at once, as a player's game knows them. */
    public int shownParallel() {
        int crafts = 0;
        for (int i = 0; i < PROCESSOR_SLOTS; i++) {
            ProcessorTier tier = shownProcessor(i);
            if (tier != null) {
                crafts += tier.crafts();
            }
        }
        return crafts;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("screen_mode", ServerScreenMode.CODEC, screenMode);
    }

    /** A server saved before the screen had modes loads with the minimal one. */
    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        screenMode = input.read("screen_mode", ServerScreenMode.CODEC).orElse(ServerScreenMode.MINIMAL);
    }

    /** The mined server keeps its screen mode. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (screenMode != ServerScreenMode.MINIMAL) {
            components.set(JasmComponents.SERVER_SCREEN_MODE.get(), screenMode);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        screenMode = components.getOrDefault(JasmComponents.SERVER_SCREEN_MODE.get(), ServerScreenMode.MINIMAL);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("screen_mode");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("parts", parts());
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ItemStack target = shown().getItem(0);
        if (!target.isEmpty()) tag.store("target", ItemStack.CODEC, ops, target);
        if (!now.isEmpty()) tag.store("now", ItemStack.CODEC, ops, now);
        if (screenMode != ServerScreenMode.MINIMAL) tag.putByte("mode", (byte) screenMode.ordinal());
        Info details = info;
        if (details != null) {
            tag.putLong("amount", details.amount());
            tag.putByte("percent", (byte) details.percent());
            tag.putInt("crafts", details.running());
            tag.putByte("status", (byte) details.status().ordinal());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        read(input);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        read(input);
    }

    private void read(ValueInput input) {
        shownParts = input.getLongOr("parts", 0);
        shownTarget = input.read("target", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        shownNow = input.read("now", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        shownMode = ServerScreenMode.of(input.getByteOr("mode", (byte) 0));
        long amount = input.getLongOr("amount", -1);
        shownInfo = amount < 0 ? null
                : new Info(amount, input.getByteOr("percent", (byte) 0), input.getIntOr("crafts", 0),
                        ServerScreenStatus.of(input.getByteOr("status", (byte) 0)));
    }
}
