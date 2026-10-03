package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.TrustList;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.ArchiveRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The Encoding Terminal: writes a recipe from its ghost grid onto a Recipe Card, pairs Decks with its
 * network, and holds the trust list for its owner's blocks on that network. Mined, it keeps its identity (so paired
 * Decks stay paired) and its trust list.
 */
public class EncodingTerminalBlockEntity extends MachineBlockEntity {
    public static final int CARD_IN = 0;
    public static final int CARD_OUT = 1;
    public static final int PAIR_IN = 2;
    public static final int PAIR_OUT = 3;
    public static final int SLOTS = 4;
    /** Cards the two piles out front can show between them. */
    public static final int PILE_CARDS = 16;
    public static final int CAPACITY = 10_000;

    /** What the menu shows about the ghost grid. */
    public static final int STATE_NONE = 0;
    public static final int STATE_SHAPED = 1;
    public static final int STATE_SHAPELESS = 2;
    /** Processing mode: everything the grid needs for a card is there. */
    public static final int STATE_PROCESSING = 3;
    /** Input slots and output slots, in the order the menu numbers their amounts. */
    public static final int AMOUNTS = ProcessingCard.INPUTS + ProcessingCard.OUTPUTS;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** The ghost grid: examples only, never real items. */
    private final SimpleContainer ghost = new SimpleContainer(9) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            EncodingTerminalBlockEntity.this.ghostChanged();
        }
    };
    /** Processing mode: what the machine gives back, examples like the grid. */
    private final SimpleContainer outputs = new SimpleContainer(ProcessingCard.OUTPUTS) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            EncodingTerminalBlockEntity.this.ghostChanged();
        }
    };
    /** Processing mode: how many of each grid item (the first nine) and each output (the last three). */
    private final int[] amounts = new int[AMOUNTS];
    /** The Access Ports the next processing card is for. Empty means the Crafting Server: an ordinary crafting card. */
    private final List<Machines.At> selected = new ArrayList<>();
    /** What the ghost grid makes right now, shown next to it. */
    private final SimpleContainer preview = new SimpleContainer(1);
    private int previewState = STATE_NONE;
    /** The preview is worked out on the first tick after loading, once recipes can be looked up. */
    private boolean previewReady;
    /** Whether this block has told the list of terminals where it stands since it was placed or loaded. */
    private boolean placed;
    /** The card piles and screen as a player's game last heard them, packed by {@link #looks()}. */
    private int shownLooks;
    /** Screens open on this terminal right now, for the lit screen out front. Not saved. */
    private int openMenus;
    private @Nullable UUID terminalId;
    private @Nullable UUID pendingPairer;
    private TrustList trust = TrustList.EMPTY;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case EncodingTerminalMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case EncodingTerminalMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case EncodingTerminalMenu.DATA_STATE -> previewState;
                case EncodingTerminalMenu.DATA_RUNNING -> running() ? 1 : 0;
                case EncodingTerminalMenu.DATA_PAIRED -> deckPaired() ? 1 : 0;
                case EncodingTerminalMenu.DATA_PROCESSING -> processing() ? 1 : 0;
                default -> index >= EncodingTerminalMenu.DATA_AMOUNTS && index < EncodingTerminalMenu.DATA_AMOUNTS + AMOUNTS
                        ? amount(index - EncodingTerminalMenu.DATA_AMOUNTS) : 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return EncodingTerminalMenu.DATA_COUNT;
        }
    };

    public EncodingTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.ENCODING_TERMINAL_ENTITY.get(), pos, state, CAPACITY);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, EncodingTerminalBlockEntity terminal) {
        ServerLevel serverLevel = (ServerLevel) level;
        if (!terminal.previewReady) {
            terminal.updatePreview(serverLevel);
        }
        if (!terminal.placed) {
            terminal.placed = true;
            terminal.claimPlace(serverLevel);
        }
        if (terminal.payForTick()) {
            terminal.processPairing();
        }
        terminal.syncLooks(terminal.looks());
    }

    /** Cards in the piles out front (one per four blank cards, sixteen at most), then one bit for "someone has it open". */
    private int looks() {
        int cards = Math.min(PILE_CARDS, (items.get(CARD_IN).getCount() + 3) / 4);
        return openMenus > 0 ? cards | 1 << 5 : cards;
    }

    /** A player opened this terminal's screen. */
    void menuOpened() {
        openMenus++;
    }

    /** A player closed this terminal's screen. */
    void menuClosed() {
        openMenus = Math.max(0, openMenus - 1);
    }

    /** How many cards the piles out front show, as a player's game knows it. */
    public int shownCards() {
        return shownLooks & 31;
    }

    /** Whether a player has this terminal open, as a player's game knows it: the screen lights up. */
    public boolean shownInUse() {
        return (shownLooks >> 5 & 1) != 0;
    }

    /**
     * Records where this terminal stands, so paired Decks can find it. A copy of a terminal that still stands
     * somewhere loaded becomes a terminal of its own.
     */
    private void claimPlace(ServerLevel level) {
        AutocraftState state = AutocraftState.get(level.getServer());
        ArchiveRecord.Placement here = new ArchiveRecord.Placement(level.dimension(), worldPosition);
        UUID id = ensureId();
        AutocraftState.Terminal known = state.terminal(id).orElse(null);
        if (known != null && !known.placement().equals(here)) {
            ServerLevel other = level.getServer().getLevel(known.placement().dimension());
            BlockPos at = known.placement().pos();
            if (other != null && other.isLoaded(at) && other.getBlockEntity(at) instanceof EncodingTerminalBlockEntity twin
                    && id.equals(twin.terminalId())) {
                renew();
                id = terminalId;
            }
        }
        state.placeTerminal(id, here);
    }

    /** Remembers who inserted the Deck while it waits for the output slot. */
    public void queuePair(Player player) {
        if (!DeckItem.isDeck(items.get(PAIR_IN)) || !MachineAccess.canUse(this, player)) {
            return;
        }
        pendingPairer = player.getUUID();
        setChanged();
        if (energy().getAmountAsInt() >= drainPerTick()) {
            processPairing();
        }
    }

    /** Pair the waiting Deck when the player may use this network and its output slot is empty. */
    public void processPairing() {
        if (!(level instanceof ServerLevel serverLevel) || pendingPairer == null || !items.get(PAIR_OUT).isEmpty()) {
            return;
        }
        ItemStack deck = items.get(PAIR_IN);
        if (!DeckItem.isDeck(deck)) {
            pendingPairer = null;
            return;
        }
        if (!pair(pendingPairer, deck)) {
            pendingPairer = null;
            setChanged();
            return;
        }
        items.set(PAIR_IN, ItemStack.EMPTY);
        items.set(PAIR_OUT, deck);
        pendingPairer = null;
        setChanged();
    }

    /**
     * Pairs {@code deck} with this terminal for {@code player}, if they own the network or are trusted on it. Their
     * Deck paired here before stops reaching the network.
     */
    public boolean pair(UUID player, ItemStack deck) {
        if (!(level instanceof ServerLevel serverLevel) || !DeckItem.isDeck(deck)) {
            return false;
        }
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        if (owner() == null || network == null || !player.equals(owner())
                && !MachineAccess.trustedBy(network, owner(), player)) {
            return false;
        }
        UUID id = ensureId();
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        if (deckId == null) {
            deckId = UUID.randomUUID();
            deck.set(JasmComponents.DECK_ID.get(), deckId);
        }
        List<UUID> connected = network.machines(EncodingTerminalBlockEntity.class).stream()
                .map(EncodingTerminalBlockEntity::ensureId).toList();
        AutocraftState state = AutocraftState.get(serverLevel.getServer());
        state.placeTerminal(id, new ArchiveRecord.Placement(serverLevel.dimension(), worldPosition));
        state.pair(id, player, deckId, connected);
        state.usedTerminal(player, id);
        state.saveNow(serverLevel.getServer());
        if (!id.equals(deck.get(JasmComponents.DECK_NETWORK.get()))) {
            deck.set(JasmComponents.DECK_NETWORK.get(), id);
        }
        return true;
    }

    /** Whether {@code player} already has a Deck paired on this terminal's network. */
    public boolean hasPairedDeck(UUID player) {
        if (!(level instanceof ServerLevel serverLevel) || Networks.at(serverLevel, worldPosition) == null) {
            return false;
        }
        List<UUID> connected = Networks.at(serverLevel, worldPosition).machines(EncodingTerminalBlockEntity.class).stream()
                .map(EncodingTerminalBlockEntity::ensureId).toList();
        return AutocraftState.get(serverLevel.getServer()).pairedPlayer(connected, player).isPresent();
    }

    /** Whether the Deck in the output slot has been paired with this terminal. */
    public boolean deckPaired() {
        ItemStack deck = items.get(PAIR_OUT);
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        return terminalId != null && deckId != null && terminalId.equals(deck.get(JasmComponents.DECK_NETWORK.get()))
                && level instanceof ServerLevel serverLevel && AutocraftState.get(serverLevel.getServer()).isActive(terminalId, deckId);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel && terminalId != null) {
            AutocraftState.get(serverLevel.getServer()).removeTerminal(terminalId, new ArchiveRecord.Placement(serverLevel.dimension(), pos));
        }
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.TERMINAL_DRAIN.getAsInt();
    }

    public SimpleContainer ghost() {
        return ghost;
    }

    public SimpleContainer preview() {
        return preview;
    }

    public SimpleContainer outputs() {
        return outputs;
    }

    // --- processing mode ---

    /** Whether the terminal writes processing cards: some Access Port is chosen instead of the Crafting Server. */
    public boolean processing() {
        return !selected.isEmpty();
    }

    public List<Machines.At> selected() {
        return List.copyOf(selected);
    }

    /** Back to crafting cards. */
    public void selectCraftingServer() {
        if (!selected.isEmpty()) {
            selected.clear();
            ghostChanged();
        }
    }

    /** Adds the port at {@code pos} to the chosen machines, or takes it out again. */
    public void toggleMachine(Machines.At machine) {
        if (!selected.remove(machine)) {
            selected.add(machine);
        }
        ghostChanged();
    }

    /** How many of processing slot {@code slot} (0-8 the grid, 9-11 the outputs). 0 where the slot is empty. */
    public int amount(int slot) {
        ItemStack there = slot < 9 ? ghost.getItem(slot) : outputs.getItem(slot - 9);
        return there.isEmpty() ? 0 : Math.max(1, amounts[slot]);
    }

    /** Sets the amount of processing slot {@code slot}, within 1 and the most a card holds. */
    public void setAmount(int slot, int amount) {
        if (slot >= 0 && slot < AMOUNTS) {
            amounts[slot] = Math.clamp(amount, 1, ProcessingCard.MAX_AMOUNT);
            setChanged();
        }
    }

    /** Puts an example item with an amount in processing slot {@code slot} (0-8 the grid, 9-11 the outputs). */
    public void setProcessingSlot(int slot, ItemStack stack, int amount) {
        if (slot < 0 || slot >= AMOUNTS) {
            return;
        }
        amounts[slot] = stack.isEmpty() ? 0 : Math.clamp(amount, 1, ProcessingCard.MAX_AMOUNT);
        ItemStack example = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (slot < 9) {
            ghost.setItem(slot, example);
        } else {
            outputs.setItem(slot - 9, example);
        }
    }

    /** The processing card the grid describes, for the chosen machines that still stand on the network, or why there is none. */
    public CardRecipes.Encoding encodeProcessing(ServerLevel level) {
        List<ProcessingCard.Amount> in = new ArrayList<>();
        for (int i = 0; i < ProcessingCard.INPUTS; i++) {
            in.add(ProcessingCard.Amount.of(ghost.getItem(i).isEmpty() ? ItemStack.EMPTY : ghost.getItem(i).copyWithCount(amount(i))));
        }
        List<ProcessingCard.Amount> out = new ArrayList<>();
        for (int i = 0; i < ProcessingCard.OUTPUTS; i++) {
            ItemStack there = outputs.getItem(i);
            out.add(ProcessingCard.Amount.of(there.isEmpty() ? ItemStack.EMPTY : there.copyWithCount(amount(9 + i))));
        }
        if (in.stream().allMatch(ProcessingCard.Amount::isEmpty)) {
            return new CardRecipes.Encoding.Refused("message.jasm.terminal.empty_grid");
        }
        if (out.stream().allMatch(ProcessingCard.Amount::isEmpty)) {
            return new CardRecipes.Encoding.Refused("message.jasm.terminal.no_output");
        }
        CableNetwork network = Networks.at(level, worldPosition);
        List<ProcessingCard.Machine> machines = new ArrayList<>();
        for (Machines.At at : selected) {
            AccessPortBlockEntity port = Machines.reach(level, network, at);
            if (port != null) {
                machines.add(new ProcessingCard.Machine(at.port(), at.side(), port.machineName(at.side()).getString()));
            }
        }
        if (machines.isEmpty()) {
            return new CardRecipes.Encoding.Refused("message.jasm.terminal.no_machine");
        }
        return new CardRecipes.Encoding.Card(new ProcessingCard(in, out, machines));
    }

    public int previewState() {
        return previewState;
    }

    public @Nullable UUID terminalId() {
        return terminalId;
    }

    /** Gives the terminal an identity if it has none yet (a newly crafted one). */
    public UUID ensureId() {
        if (terminalId == null) {
            terminalId = UUID.randomUUID();
            setChanged();
        }
        return terminalId;
    }

    /** A copy of another terminal becomes a terminal of its own. */
    public void renew() {
        terminalId = UUID.randomUUID();
        setChanged();
    }

    public TrustList trust() {
        return trust;
    }

    public void setTrust(TrustList trust) {
        this.trust = trust;
        setChanged();
    }

    @Override
    protected void onOwnerChanged(@Nullable UUID previous) {
        if (previous != null) {
            setTrust(TrustList.EMPTY);
            if (terminalId != null && level instanceof ServerLevel serverLevel) {
                AutocraftState state = AutocraftState.get(serverLevel.getServer());
                state.unpairTerminal(terminalId);
                state.saveNow(serverLevel.getServer());
            }
        }
    }

    /** The owner, and everyone the owner trusts here or at another of their terminals on the network. */
    public boolean isAuthorized(Player player) {
        return MachineAccess.canUse(this, player);
    }

    private void ghostChanged() {
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            updatePreview(serverLevel);
        }
    }

    /** Works out what the ghost grid makes. */
    public void updatePreview(ServerLevel level) {
        previewReady = true;
        if (processing()) {
            preview.setItem(0, ItemStack.EMPTY);
            previewState = !ghost.isEmpty() && !outputs.isEmpty() ? STATE_PROCESSING : STATE_NONE;
            return;
        }
        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            grid.add(ghost.getItem(i));
        }
        CardRecipes.Encoding encoding = CardRecipes.encode(level, grid);
        if (encoding instanceof CardRecipes.Encoding.Card(Card found) && found instanceof RecipeCard card) {
            preview.setItem(0, card.result());
            previewState = card.shapeless() ? STATE_SHAPELESS : STATE_SHAPED;
        } else {
            preview.setItem(0, ItemStack.EMPTY);
            previewState = STATE_NONE;
        }
    }

    /**
     * Writes the ghost grid's recipe onto the card in the input slot (an Empty card, or a Filled one to overwrite)
     * and moves it to the output slot. Returns the message to show, or null when it worked.
     */
    public @Nullable String encode(ServerLevel level) {
        if (!running()) {
            return "message.jasm.terminal.no_power";
        }
        ItemStack input = items.get(CARD_IN);
        if (!input.is(JasmItems.RECIPE_CARD.get()) && !input.is(JasmItems.FILLED_RECIPE_CARD.get())) {
            return "message.jasm.terminal.no_card";
        }
        if (!items.get(CARD_OUT).isEmpty()) {
            return "message.jasm.terminal.output_full";
        }
        CardRecipes.Encoding encoding;
        if (processing()) {
            encoding = encodeProcessing(level);
        } else {
            List<ItemStack> grid = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                grid.add(ghost.getItem(i));
            }
            encoding = CardRecipes.encode(level, grid);
        }
        if (encoding instanceof CardRecipes.Encoding.Refused(String messageKey)) {
            return messageKey;
        }
        Card card = ((CardRecipes.Encoding.Card) encoding).card();
        ItemStack filled = new ItemStack(JasmItems.FILLED_RECIPE_CARD.get());
        Card.set(filled, card);
        input.shrink(1);
        items.set(CARD_OUT, filled);
        setChanged();
        return null;
    }

    public ContainerData data() {
        return data;
    }

    /**
     * A Filled card put in the top slot shows its recipe in the grid, ready to change and write again. A processing
     * card also brings back its amounts, outputs and machines.
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        if (slot != CARD_IN || !stack.is(JasmItems.FILLED_RECIPE_CARD.get())) {
            return;
        }
        Card found = Card.of(stack);
        if (found instanceof RecipeCard card) {
            selected.clear();
            List<ItemStack> inputs = card.inputs();
            for (int i = 0; i < 9; i++) {
                ghost.setItem(i, inputs.get(i).isEmpty() ? ItemStack.EMPTY : inputs.get(i).copyWithCount(1));
            }
        } else if (found instanceof ProcessingCard card) {
            selected.clear();
            selected.addAll(card.spots());
            for (int i = 0; i < ProcessingCard.INPUTS; i++) {
                setProcessingSlot(i, card.inputs().get(i).stack(), card.inputs().get(i).count());
            }
            for (int i = 0; i < ProcessingCard.OUTPUTS; i++) {
                setProcessingSlot(9 + i, card.outputs().get(i).stack(), card.outputs().get(i).count());
            }
        }
    }

    public static boolean accepts(int slot, ItemStack stack) {
        return switch (slot) {
            case CARD_IN -> stack.is(JasmItems.RECIPE_CARD.get()) || stack.is(JasmItems.FILLED_RECIPE_CARD.get());
            case PAIR_IN -> DeckItem.isDeck(stack);
            default -> false;
        };
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
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
        if (level instanceof ServerLevel serverLevel) {
            AutocraftState state = AutocraftState.get(serverLevel.getServer());
            state.placeTerminal(ensureId(), new ArchiveRecord.Placement(serverLevel.dimension(), worldPosition));
            state.usedTerminal(inventory.player.getUUID(), ensureId());
        }
        return new EncodingTerminalMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        ValueOutput.TypedOutputList<ItemStack> ghostItems = output.list("ghost", ItemStack.OPTIONAL_CODEC);
        for (int i = 0; i < 9; i++) {
            ghostItems.add(ghost.getItem(i));
        }
        ValueOutput.TypedOutputList<ItemStack> outputItems = output.list("outputs", ItemStack.OPTIONAL_CODEC);
        for (int i = 0; i < ProcessingCard.OUTPUTS; i++) {
            outputItems.add(outputs.getItem(i));
        }
        output.putIntArray("amounts", amounts.clone());
        output.store("selected_machines", Machines.At.CODEC.listOf(), List.copyOf(selected));
        output.storeNullable("terminal", UUIDUtil.CODEC, terminalId);
        output.storeNullable("pending_pairer", UUIDUtil.CODEC, pendingPairer);
        output.store("trust", TrustList.CODEC, trust);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        int i = 0;
        for (ItemStack stack : input.listOrEmpty("ghost", ItemStack.OPTIONAL_CODEC)) {
            if (i < 9) {
                ghost.getItems().set(i++, stack);
            }
        }
        int o = 0;
        for (ItemStack stack : input.listOrEmpty("outputs", ItemStack.OPTIONAL_CODEC)) {
            if (o < ProcessingCard.OUTPUTS) {
                outputs.getItems().set(o++, stack);
            }
        }
        int[] saved = input.getIntArray("amounts").orElse(new int[0]);
        for (int a = 0; a < AMOUNTS; a++) {
            amounts[a] = a < saved.length ? saved[a] : 0;
        }
        selected.clear();
        selected.addAll(input.read("selected_machines", Machines.At.CODEC.listOf()).orElse(List.of()));
        terminalId = input.read("terminal", UUIDUtil.CODEC).orElse(null);
        pendingPairer = input.read("pending_pairer", UUIDUtil.CODEC).orElse(null);
        trust = input.read("trust", TrustList.CODEC).orElse(TrustList.EMPTY);
    }

    /** The mined item keeps its identity and trust list, as well as its charge. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (terminalId != null) {
            components.set(JasmComponents.TERMINAL_ID.get(), terminalId);
        }
        if (!trust.entries().isEmpty()) {
            components.set(JasmComponents.TRUST.get(), trust);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        terminalId = components.get(JasmComponents.TERMINAL_ID.get());
        trust = components.getOrDefault(JasmComponents.TRUST.get(), TrustList.EMPTY);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("terminal");
        output.discard("trust");
    }

    // Players only get what the front shows, not the cards, recipe or trust list.
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
}
