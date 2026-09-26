package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Holds 16 Filled Recipe Cards for its network. Without power, the network can't see them. */
public class RecipeRackBlockEntity extends MachineBlockEntity {
    public static final int SLOTS = 16;
    public static final int CAPACITY = 5_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case RecipeRackMenu.DATA_ENERGY_LOW -> energy.getAmountAsInt() & 0xFFFF;
                case RecipeRackMenu.DATA_ENERGY_HIGH -> energy.getAmountAsInt() >>> 16;
                case RecipeRackMenu.DATA_RUNNING -> running() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return RecipeRackMenu.DATA_COUNT;
        }
    };

    public RecipeRackBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.RECIPE_RACK_ENTITY.get(), pos, state, CAPACITY);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, RecipeRackBlockEntity rack) {
        rack.payForTick();
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.RACK_DRAIN.getAsInt();
    }

    /** The cards the network can see: none while the rack has no power. */
    public List<RecipeCard> cards() {
        List<RecipeCard> cards = new ArrayList<>();
        if (running()) {
            for (ItemStack stack : items) {
                RecipeCard card = stack.get(JasmComponents.RECIPE_CARD.get());
                if (card != null) {
                    cards.add(card);
                }
            }
        }
        return cards;
    }

    public ContainerData data() {
        return data;
    }

    public static boolean accepts(ItemStack stack) {
        return stack.is(JasmItems.FILLED_RECIPE_CARD.get()) && stack.has(JasmComponents.RECIPE_CARD.get());
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
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
        return new RecipeRackMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
    }
}
