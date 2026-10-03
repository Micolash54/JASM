package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import net.minecraft.server.level.ServerLevel;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Holds 16 Filled Recipe Cards for its network. Without power, the network can't see them. */
public class RecipeRackBlockEntity extends MachineBlockEntity {
    public static final int SLOTS = 16;
    public static final int CAPACITY = 5_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** One bit per slot: a processing card none of whose machines can be reached. Worked out once a second. */
    private int missing;
    /** One bit per slot that holds a card, as last sent to players. It's all their game needs to draw the cards. */
    private int shownCards;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case RecipeRackMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case RecipeRackMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case RecipeRackMenu.DATA_RUNNING -> running() ? 1 : 0;
                case RecipeRackMenu.DATA_MISSING -> missing;
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
        if (level.getGameTime() % 20 == 0) {
            rack.checkMachines((ServerLevel) level);
        }
        int filled = rack.filledSlots();
        if (filled != rack.shownCards) {
            rack.shownCards = filled;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private int filledSlots() {
        int bits = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (!items.get(i).isEmpty()) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /** Whether the slot holds a card, as far as a player's game knows. */
    public boolean showsCard(int slot) {
        return (shownCards & (1 << slot)) != 0;
    }

    /** Marks the processing cards whose machines can't be reached, for the screen. */
    private void checkMachines(ServerLevel level) {
        CableNetwork network = Networks.at(level, worldPosition);
        int bits = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (Card.of(items.get(i)) instanceof ProcessingCard card && !Jobs.reachable(level, network, card)) {
                bits |= 1 << i;
            }
        }
        missing = bits;
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.RACK_DRAIN.getAsInt();
    }

    /** The cards the network can see: none while the rack has no power. */
    public List<Card> cards() {
        List<Card> cards = new ArrayList<>();
        if (running()) {
            for (ItemStack stack : items) {
                Card card = Card.of(stack);
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
        return stack.is(JasmItems.FILLED_RECIPE_CARD.get()) && Card.of(stack) != null;
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

    // Players only get which slots are filled, not the cards themselves.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cards", filledSlots());
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shownCards = input.getIntOr("cards", 0);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        shownCards = input.getIntOr("cards", 0);
    }
}
