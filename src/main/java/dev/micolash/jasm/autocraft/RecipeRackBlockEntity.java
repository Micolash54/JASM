package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmItems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
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
import org.jspecify.annotations.Nullable;

/** Holds 16 Filled Recipe Cards for its network. Without power, the network can't see them. */
public class RecipeRackBlockEntity extends MachineBlockEntity {
    public static final int SLOTS = 16;
    public static final int CAPACITY = 5_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** One bit per slot: a processing card none of whose machines can be reached. Worked out once a second. */
    private int missing;
    /** One bit per slot that holds a card, as last sent to players. */
    private int shownCards;
    /**
     * What each slot's card makes, one of it, as last sent to players: their game draws it on the card. Empty for an empty
     * slot or a card with nothing readable to show.
     */
    private final ItemStack[] shownMakes = new ItemStack[SLOTS];
    /** The stacks {@link #shownMakes} was read from, so a tick notices a changed slot without reading every card. */
    private final ItemStack[] seen = new ItemStack[SLOTS];
    /** Goes up each time a player's game is sent new cards, so its renderer knows to look again. */
    private int shownVersion;
    /** The client renderer's own things for this rack, kept between frames. Never saved or sent. */
    private @Nullable Object renderCache;
    /** A name the player gave the rack; empty for the block's own name. */
    private String label = "";

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case RecipeRackMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case RecipeRackMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case RecipeRackMenu.DATA_RUNNING -> running() ? 1 : 0;
                case RecipeRackMenu.DATA_MISSING -> missing;
                case RecipeRackMenu.DATA_DRAIN -> drainPerTick();
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
        Arrays.fill(shownMakes, ItemStack.EMPTY);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, RecipeRackBlockEntity rack) {
        rack.payForTick();
        if (level.getGameTime() % 20 == 0) {
            rack.checkMachines((ServerLevel) level);
        }
        if (rack.updateShown()) {
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Brings what players are shown up to date with the slots, reading only the cards whose stack changed. Whether
     * anything players see is different.
     */
    private boolean updateShown() {
        boolean changed = false;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = items.get(i);
            boolean filled = !stack.isEmpty();
            boolean wasFilled = (shownCards & (1 << i)) != 0;
            if (stack == seen[i] && filled == wasFilled) {
                continue;
            }
            seen[i] = stack;
            if (filled != wasFilled) {
                shownCards ^= 1 << i;
                changed = true;
            }
            ItemStack makes = makes(stack);
            if (!ItemStack.matches(makes, shownMakes[i])) {
                shownMakes[i] = makes;
                changed = true;
            }
        }
        return changed;
    }

    /** One of what the card {@code stack} makes, or empty for no card or a card that makes nothing readable. */
    private static ItemStack makes(ItemStack stack) {
        Card card = stack.isEmpty() ? null : Card.of(stack);
        if (card == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = card.result();
        return result.isEmpty() ? ItemStack.EMPTY : result.copyWithCount(1);
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

    /** What the slot's card makes, as far as a player's game knows; empty for none. Not to be changed. */
    public ItemStack shownMakes(int slot) {
        return shownMakes[slot];
    }

    /** Goes up each time a player's game is sent new cards. */
    public int shownVersion() {
        return shownVersion;
    }

    public @Nullable Object renderCache() {
        return renderCache;
    }

    public void setRenderCache(@Nullable Object cache) {
        renderCache = cache;
    }

    /** Marks the processing cards no port serves with a machine, for the screen, and brings older cards up to date. */
    private void checkMachines(ServerLevel level) {
        CableNetwork network = Networks.at(level, worldPosition);
        int bits = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (!(Card.of(items.get(i)) instanceof ProcessingCard card)) {
                continue;
            }
            if (card.namesPorts()) {
                card = convert(level, network, i, card);
            }
            if (!Jobs.reachable(level, network, card)) {
                bits |= 1 << i;
            }
        }
        missing = bits;
    }

    /**
     * An older card in slot {@code slot} names the ports it was written for. Each such port standing on the network
     * starts serving its recipe. A port in an unloaded spot is waited for; a loaded spot with no port is let go, and
     * the recipe stays here for a port to pick. The card is written back without the ports it is done with.
     */
    private ProcessingCard convert(ServerLevel level, @Nullable CableNetwork network, int slot, ProcessingCard card) {
        if (network == null) {
            return card;
        }
        List<ProcessingCard.Machine> waiting = new ArrayList<>();
        for (ProcessingCard.Machine machine : card.machines()) {
            // an older card names no dimension: its ports stand in the rack's
            ServerLevel there = machine.at().level(level);
            if (!Machines.running(there, machine.pos())) {
                waiting.add(machine);
                continue;
            }
            AccessPortBlockEntity port = Machines.port(there, network, machine.pos(), machine.side());
            // a port already serving its most recipes turns it down: it stays here unpicked, like one with no port
            if (port != null) {
                port.select(card);
            }
        }
        if (waiting.size() == card.machines().size()) {
            return card;
        }
        ProcessingCard left = waiting.isEmpty() ? card.clean() : card.at(waiting);
        ItemStack stack = items.get(slot).copy();
        Card.set(stack, left);
        items.set(slot, stack);
        setChanged();
        return left;
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

    /** An older card is brought up to date as it goes in; ports still waiting are checked again each second. */
    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        if (level instanceof ServerLevel server && Card.of(stack) instanceof ProcessingCard card && card.namesPorts()) {
            convert(server, Networks.at(server, worldPosition), slot, card);
        }
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
    public Component getName() {
        return label.isEmpty() ? getDefaultName() : Component.literal(label);
    }

    /** The name the player gave the rack, or empty. */
    public String label() {
        return label;
    }

    /** Names the rack; an empty name brings back the block's own. Cut to the longest name the rename panel takes. */
    public void setLabel(String label) {
        String cleaned = label.strip();
        this.label = cleaned.length() > AccessPortMenu.MAX_NAME ? cleaned.substring(0, AccessPortMenu.MAX_NAME) : cleaned;
        setChanged();
    }

    @Override
    public boolean sendsPosToScreen() {
        return true;
    }

    /** The screen is told where the rack stands and its name, for the rename panel. */
    @Override
    public void writeScreenData(RegistryFriendlyByteBuf buf) {
        super.writeScreenData(buf);
        buf.writeUtf(label, AccessPortMenu.MAX_NAME);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new RecipeRackMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putString("label", label);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        // A rack saved before it could be renamed has no label: it keeps a name given on an anvil, if any.
        Component custom = getCustomName();
        label = input.getStringOr("label", custom == null ? "" : custom.getString());
    }

    /** The mined rack keeps its name. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CUSTOM_NAME, label.isEmpty() ? null : Component.literal(label));
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component name = components.get(DataComponents.CUSTOM_NAME);
        label = name == null ? "" : name.getString();
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("label");
    }

    /** The update tag's key for what the card in slot {@code i} makes. */
    private static final String[] MAKES_KEYS = new String[SLOTS];

    static {
        for (int i = 0; i < SLOTS; i++) MAKES_KEYS[i] = "makes" + i;
    }

    // Players only get which slots are filled and what each card makes, not the cards themselves.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cards", filledSlots());
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack makes = makes(items.get(i));
            if (!makes.isEmpty()) tag.store(MAKES_KEYS[i], ItemStack.CODEC, ops, makes);
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        readShown(input);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        readShown(input);
    }

    private void readShown(ValueInput input) {
        shownCards = input.getIntOr("cards", 0);
        for (int i = 0; i < SLOTS; i++) {
            // an item this game can't read shows as a plain card
            shownMakes[i] = (shownCards & (1 << i)) == 0 ? ItemStack.EMPTY : input.read(MAKES_KEYS[i], ItemStack.CODEC).orElse(ItemStack.EMPTY);
        }
        shownVersion++;
    }
}
