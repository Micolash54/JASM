package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import org.jspecify.annotations.Nullable;

/**
 * An Advanced Crafting Server: a Crafting Server with ten Processor slots and ten Storage Module slots. Players also
 * see, on its front screen, what the job makes and what is crafting at that moment.
 */
public class AdvancedCraftingServerBlockEntity extends CraftingServerBlockEntity {
    public static final int PROCESSOR_SLOTS = 10;
    public static final int MEMORY_SLOTS = 10;
    public static final int SLOTS = PROCESSOR_SLOTS + MEMORY_SLOTS;
    public static final int CAPACITY = 300_000;
    /** Ticks between checks of what is crafting right now. */
    private static final int NOW_EVERY = 5;

    /** What players were last told: parts and power packed by {@link #parts()}, and the two items on the screen. */
    private long sentParts = -1;
    private ItemStack sentTarget = ItemStack.EMPTY;
    private ItemStack sentNow = ItemStack.EMPTY;
    private ItemStack now = ItemStack.EMPTY;
    /** Whether the top half's screen was last set on; null until checked after loading. */
    private @Nullable Boolean screenOn;

    /** The same, as a player's game last heard it. */
    private long shownParts;
    private ItemStack shownTarget = ItemStack.EMPTY;
    private ItemStack shownNow = ItemStack.EMPTY;

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

    /** A bigger machine: twice the Crafting Server's own use, plus its Processors as usual. */
    @Override
    public int drainPerTick() {
        return super.drainPerTick() + JasmConfig.SERVER_DRAIN.getAsInt();
    }

    @Override
    protected void syncShown(ServerLevel level) {
        CraftingJob job = job();
        boolean on = job != null;
        if (screenOn == null || screenOn != on) {
            screenOn = on;
            AdvancedCraftingServerBlock.setScreen(level, worldPosition, on);
        }
        if (job == null) {
            now = ItemStack.EMPTY;
        } else if ((level.getGameTime() + worldPosition.asLong()) % NOW_EVERY == 0) {
            // The last of the crafts running now is the step being made at this moment; the final item comes first.
            List<CraftingJob.Now> rows = job.now();
            now = rows.isEmpty() ? ItemStack.EMPTY : rows.getLast().what().toStack(1);
        }
        ItemStack target = shown().getItem(0);
        long parts = parts();
        if (parts != sentParts || !ItemStack.isSameItemSameComponents(target, sentTarget) || !ItemStack.isSameItemSameComponents(now, sentNow)) {
            sentParts = parts;
            sentTarget = target.copy();
            sentNow = now.copy();
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

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("parts", parts());
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ItemStack target = shown().getItem(0);
        if (!target.isEmpty()) tag.store("target", ItemStack.CODEC, ops, target);
        if (!now.isEmpty()) tag.store("now", ItemStack.CODEC, ops, now);
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
    }
}
