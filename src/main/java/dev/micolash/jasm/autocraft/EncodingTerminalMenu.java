package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.TrustList;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Encoding Terminal's menu: card in and out, the pairing slot, the 3×3 ghost grid and what it makes, then the
 * player's inventory (27) and hotbar (9). Ghost slots copy what is clicked into them and never take the item.
 */
public class EncodingTerminalMenu extends AbstractContainerMenu implements Notices.Board {
    public static final int GRID_X = 30;
    public static final int GRID_Y = 18;
    public static final int PREVIEW_X = 124;
    public static final int PREVIEW_Y = 36;
    public static final int CARD_X = 8;
    public static final int CARD_IN_Y = 18;
    public static final int CARD_OUT_Y = 54;
    public static final int PAIR_X = 152;
    public static final int PAIR_Y = 18;
    public static final int INVENTORY_Y = 108;
    /** Where the terminal's messages show, under the grid. */
    public static final int MESSAGE_Y = 76;

    /** Processing mode: the column of what the machine gives back, beside the grid. */
    public static final int OUTPUTS_X = 102;
    public static final int OUTPUTS_Y = 18;

    public static final int SLOT_CARD_IN = 0;
    public static final int SLOT_CARD_OUT = 1;
    public static final int SLOT_PAIR = 2;
    public static final int SLOT_GHOST = 3;
    public static final int SLOT_PREVIEW = SLOT_GHOST + 9;
    public static final int SLOT_OUTPUTS = SLOT_PREVIEW + 1;
    public static final int SLOT_INVENTORY = SLOT_OUTPUTS + ProcessingCard.OUTPUTS;

    public static final int BUTTON_ENCODE = 0;
    public static final int BUTTON_CLEAR = 1;
    /** Back to crafting cards. */
    public static final int BUTTON_CRAFTING_SERVER = 2;
    /** {@code BUTTON_AMOUNT + slot * 4 + op}: processing slot 0-11 (grid, then outputs); op 0 +1, 1 -1, 2 +10, 3 -10. */
    public static final int BUTTON_AMOUNT = 100;
    /** {@code BUTTON_MACHINE + i}: choose or drop the {@code i}th machine of the list last sent. */
    public static final int BUTTON_MACHINE = 1000;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_STATE = 2;
    static final int DATA_RUNNING = 3;
    static final int DATA_PAIRED = 4;
    static final int DATA_PROCESSING = 5;
    static final int DATA_AMOUNTS = 6;
    static final int DATA_COUNT = DATA_AMOUNTS + EncodingTerminalBlockEntity.AMOUNTS;

    /** Ticks between refreshes of the machine list. */
    private static final int MACHINES_EVERY = 20;

    /** Messages the screen can show, by number; 0 is none. */
    public static final List<String> MESSAGES = List.of("", "message.jasm.terminal.no_power", "message.jasm.terminal.no_card",
            "message.jasm.terminal.output_full", "message.jasm.terminal.empty_grid", "message.jasm.terminal.no_recipe",
            "message.jasm.terminal.encoded", "message.jasm.terminal.no_output", "message.jasm.terminal.no_machine");

    /** An Access Port in the machine list: where, its name, whether it still stands on the network, and whether it is chosen. */
    public record MachineView(Machines.At at, String name, boolean present, boolean selected) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MachineView> STREAM_CODEC = StreamCodec.composite(
                Machines.At.STREAM_CODEC, MachineView::at,
                ByteBufCodecs.stringUtf8(128), MachineView::name,
                ByteBufCodecs.BOOL, MachineView::present,
                ByteBufCodecs.BOOL, MachineView::selected,
                MachineView::new);
    }

    private static final Identifier EMPTY_CARD = Jasm.id("container/empty_card");
    private static final Identifier EMPTY_DECK = Jasm.id("container/empty_deck");

    private final Notices.Shown notices = new Notices.Shown();
    private final Container container;
    private final Container ghost;
    private final Container outputs;
    private final ContainerData data;
    /** The last message for this screen, and a count that goes up with each one so a repeat still shows. */
    private final ContainerData feedback = new SimpleContainerData(2);
    private final ContainerLevelAccess access;
    private final @Nullable EncodingTerminalBlockEntity terminal;
    private final Player player;
    /** Server side: whether the trust list was sent to this screen yet. */
    private boolean trustSent;
    /** Client side: what the server said about trust. */
    private boolean owner;
    private TrustList trust = TrustList.EMPTY;
    /** The Access Ports in the list: on the server the last list sent, on the client the last received. */
    private List<MachineView> machines = List.of();
    /** Server side: send the machine list with the next update (it opened, or the choice changed). */
    private boolean machinesDue = true;
    /** Most machines listed. */
    public static final int MAX_MACHINES = 256;

    /** Server side. */
    public EncodingTerminalMenu(int containerId, Inventory inventory, EncodingTerminalBlockEntity terminal, ContainerLevelAccess access) {
        this(containerId, inventory, terminal, terminal.ghost(), terminal.outputs(), terminal.preview(), terminal.data(), access, terminal);
    }

    /** Client side. */
    public EncodingTerminalMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(EncodingTerminalBlockEntity.SLOTS) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return EncodingTerminalBlockEntity.accepts(slot, stack);
            }
        }, new SimpleContainer(9), new SimpleContainer(ProcessingCard.OUTPUTS), new SimpleContainer(1), new SimpleContainerData(DATA_COUNT),
                ContainerLevelAccess.NULL, null);
    }

    private EncodingTerminalMenu(int containerId, Inventory inventory, Container container, Container ghost, Container outputs,
            Container preview, ContainerData data, ContainerLevelAccess access, @Nullable EncodingTerminalBlockEntity terminal) {
        super(JasmMenus.ENCODING_TERMINAL.get(), containerId);
        this.container = container;
        this.ghost = ghost;
        this.outputs = outputs;
        this.data = data;
        this.access = access;
        this.terminal = terminal;
        this.player = inventory.player;
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.CARD_IN, CARD_X, CARD_IN_Y, EMPTY_CARD));
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.CARD_OUT, CARD_X, CARD_OUT_Y, null));
        addSlot(new MachineSlot(container, EncodingTerminalBlockEntity.PAIR, PAIR_X, PAIR_Y, EMPTY_DECK));
        for (int i = 0; i < 9; i++) {
            addSlot(new FakeSlot(ghost, i, GRID_X + (i % 3) * 18, GRID_Y + (i / 3) * 18));
        }
        addSlot(new FakeSlot(preview, 0, PREVIEW_X, PREVIEW_Y, () -> !processing()));
        for (int i = 0; i < ProcessingCard.OUTPUTS; i++) {
            addSlot(new FakeSlot(outputs, i, OUTPUTS_X, OUTPUTS_Y + i * 18, this::processing));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
        addDataSlots(feedback);
    }

    /** The last message's key, or empty. */
    public String message() {
        int index = feedback.get(0);
        return index > 0 && index < MESSAGES.size() ? MESSAGES.get(index) : "";
    }

    /** Goes up with every message. */
    public int messageCount() {
        return feedback.get(1);
    }

    private void tell(String key) {
        feedback.set(0, Math.max(0, MESSAGES.indexOf(key)));
        feedback.set(1, (feedback.get(1) + 1) & 0x7FFF);
    }

    public @Nullable EncodingTerminalBlockEntity terminal() {
        return terminal;
    }

    /**
     * The trust list goes to the screen with the first update after opening; the machine list then, whenever the
     * choice changes, and once a second if the ports changed.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (terminal == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!trustSent) {
            trustSent = true;
            TerminalAccess.send(serverPlayer, this, terminal);
        }
        if (machinesDue || serverPlayer.level().getGameTime() % MACHINES_EVERY == 0) {
            machinesDue = false;
            List<MachineView> now = currentMachines((ServerLevel) serverPlayer.level());
            if (!now.equals(machines)) {
                machines = now;
                if (serverPlayer.connection.hasChannel(CraftPayloads.TerminalMachines.TYPE)) {
                    PacketDistributor.sendToPlayer(serverPlayer, new CraftPayloads.TerminalMachines(containerId, now));
                }
            }
        }
    }

    public void setTrustView(boolean owner, TrustList trust) {
        this.owner = owner;
        this.trust = trust;
    }

    /** Client side: whether the viewer owns this terminal. */
    public boolean owner() {
        return owner;
    }

    /** Client side: the trusted players, as last sent. */
    public TrustList trust() {
        return trust;
    }

    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    public int capacity() {
        return EncodingTerminalBlockEntity.CAPACITY;
    }

    /** {@link EncodingTerminalBlockEntity#STATE_NONE}, {@code STATE_SHAPED} or {@code STATE_SHAPELESS}. */
    public int recipeState() {
        return data.get(DATA_STATE);
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** Whether the Deck in the pairing slot is paired with this terminal. */
    public boolean paired() {
        return data.get(DATA_PAIRED) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        if (terminal == null) {
            return true;
        }
        return !terminal.isRemoved() && stillValid(access, player, JasmBlocks.ENCODING_TERMINAL.get()) && terminal.isAuthorized(player);
    }

    /**
     * Ghost slots: holding an item copies it in, empty-handed (or right-click) clears the slot. In processing mode the
     * copy keeps the held stack's count, and the output column works the same. The preview can't be taken.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        boolean grid = slotIndex >= SLOT_GHOST && slotIndex < SLOT_PREVIEW;
        boolean output = slotIndex >= SLOT_OUTPUTS && slotIndex < SLOT_INVENTORY;
        if (grid || output) {
            if ((input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) && (grid || processing())) {
                ItemStack carried = getCarried();
                boolean clear = carried.isEmpty() || buttonNum == 1 || input == ContainerInput.QUICK_MOVE;
                int slot = grid ? slotIndex - SLOT_GHOST : 9 + slotIndex - SLOT_OUTPUTS;
                ItemStack example = clear ? ItemStack.EMPTY : carried;
                if (terminal != null && processing()) {
                    terminal.setProcessingSlot(slot, example, carried.getCount());
                } else if (grid) {
                    ghost.setItem(slot, example.isEmpty() ? ItemStack.EMPTY : example.copyWithCount(1));
                }
            }
            return;
        }
        if (slotIndex == SLOT_PREVIEW) {
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /**
     * Puts {@code stack} (or nothing) in ghost slot {@code slot}, 0-8 the grid and 9-11 the outputs: for JEI's recipe
     * button and drag-and-drop. In processing mode it keeps the stack's count.
     */
    public void setGhost(int slot, ItemStack stack) {
        if (terminal != null && processing() && slot >= 0 && slot < EncodingTerminalBlockEntity.AMOUNTS) {
            terminal.setProcessingSlot(slot, stack, stack.getCount());
        } else if (slot >= 0 && slot < 9) {
            ghost.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
    }

    /**
     * Processing mode: fills the grid and the outputs from a recipe (JEI's "+"). The same item in several slots is
     * added up; what doesn't fit (more than nine kinds in, three out) is left out.
     */
    public void setProcessing(List<ItemStack> inputs, List<ItemStack> outputs) {
        if (terminal == null || !processing()) {
            return;
        }
        List<ItemStack> in = merged(inputs, ProcessingCard.INPUTS);
        List<ItemStack> out = merged(outputs, ProcessingCard.OUTPUTS);
        for (int i = 0; i < ProcessingCard.INPUTS; i++) {
            ItemStack stack = i < in.size() ? in.get(i) : ItemStack.EMPTY;
            terminal.setProcessingSlot(i, stack, stack.getCount());
        }
        for (int i = 0; i < ProcessingCard.OUTPUTS; i++) {
            ItemStack stack = i < out.size() ? out.get(i) : ItemStack.EMPTY;
            terminal.setProcessingSlot(9 + i, stack, stack.getCount());
        }
    }

    private static List<ItemStack> merged(List<ItemStack> stacks, int most) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack same = merged.stream().filter(s -> ItemStack.isSameItemSameComponents(s, stack)).findFirst().orElse(null);
            if (same != null) {
                same.setCount(same.getCount() + stack.getCount());
            } else if (merged.size() < most) {
                merged.add(stack.copy());
            }
        }
        return merged;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (terminal == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (id >= BUTTON_MACHINE && id < BUTTON_MACHINE + machines.size()) {
            terminal.toggleMachine(machines.get(id - BUTTON_MACHINE).at());
            machinesDue = true;
            return true;
        }
        if (id >= BUTTON_AMOUNT && id < BUTTON_AMOUNT + EncodingTerminalBlockEntity.AMOUNTS * 4) {
            int slot = (id - BUTTON_AMOUNT) / 4;
            int change = switch ((id - BUTTON_AMOUNT) % 4) {
                case 0 -> 1;
                case 1 -> -1;
                case 2 -> 10;
                default -> -10;
            };
            if (processing() && terminal.amount(slot) > 0) {
                terminal.setAmount(slot, terminal.amount(slot) + change);
            }
            return true;
        }
        switch (id) {
            case BUTTON_ENCODE -> {
                String problem = terminal.encode((ServerLevel) serverPlayer.level());
                tell(problem == null ? "message.jasm.terminal.encoded" : problem);
                return true;
            }
            case BUTTON_CLEAR -> {
                for (int i = 0; i < EncodingTerminalBlockEntity.AMOUNTS; i++) {
                    terminal.setProcessingSlot(i, ItemStack.EMPTY, 0);
                }
                return true;
            }
            case BUTTON_CRAFTING_SERVER -> {
                terminal.selectCraftingServer();
                machinesDue = true;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // --- the machine list ---

    /** Whether the terminal writes processing cards (some machine is chosen). */
    public boolean processing() {
        return data.get(DATA_PROCESSING) != 0;
    }

    /** How many of processing slot {@code slot} (0-8 the grid, 9-11 the outputs); 0 where empty. */
    public int amount(int slot) {
        return slot >= 0 && slot < EncodingTerminalBlockEntity.AMOUNTS ? data.get(DATA_AMOUNTS + slot) : 0;
    }

    /** The Access Ports on the terminal's network that the viewer may use, and chosen ones that are gone. */
    public List<MachineView> machines() {
        return machines;
    }

    /** Client side: the list the server sent. */
    public void setMachines(List<MachineView> machines) {
        this.machines = List.copyOf(machines);
    }

    /** Server side: the machine list as it is now, one entry per machine touching a port. */
    private List<MachineView> currentMachines(ServerLevel level) {
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        List<Machines.At> chosen = terminal.selected();
        List<MachineView> views = new ArrayList<>();
        if (network != null) {
            for (AccessPortBlockEntity port : Machines.ports(network)) {
                if (MachineAccess.canUse(port, player)) {
                    for (net.minecraft.core.Direction side : port.machineSides()) {
                        Machines.At at = new Machines.At(port.getBlockPos(), side);
                        views.add(new MachineView(at, port.machineName(side).getString(), true, chosen.contains(at)));
                    }
                }
            }
        }
        views.sort(Comparator.comparing(MachineView::name).thenComparing(v -> v.at().port().asLong()).thenComparing(v -> v.at().side()));
        for (Machines.At at : chosen) {
            if (views.stream().noneMatch(v -> v.at().equals(at))) {
                views.add(new MachineView(at, "", false, true));
            }
        }
        return views.size() > MAX_MACHINES ? views.subList(0, MAX_MACHINES) : views;
    }

    /**
     * Shift-click: cards go to the input slot and Crafting Decks to the pairing slot; out of the terminal, items go
     * to the hotbar first, then the inventory.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem() || clicked instanceof FakeSlot) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        int hotbar = SLOT_INVENTORY + 27;
        boolean moved;
        if (index < SLOT_GHOST) {
            moved = moveItemStackTo(stack, hotbar, hotbar + 9, false) || moveItemStackTo(stack, SLOT_INVENTORY, hotbar, false);
        } else if (EncodingTerminalBlockEntity.accepts(EncodingTerminalBlockEntity.CARD_IN, stack)) {
            moved = moveItemStackTo(stack, SLOT_CARD_IN, SLOT_CARD_IN + 1, false);
        } else if (DeckItem.isCrafting(stack)) {
            moved = moveItemStackTo(stack, SLOT_PAIR, SLOT_PAIR + 1, false);
        } else {
            moved = false;
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            clicked.setByPlayer(ItemStack.EMPTY);
        } else {
            clicked.setChanged();
        }
        return before;
    }

    /** A slot that only takes what the terminal accepts there, with a faint picture while empty. */
    private static final class MachineSlot extends Slot {
        private final @Nullable Identifier icon;

        MachineSlot(Container container, int index, int x, int y, @Nullable Identifier icon) {
            super(container, index, x, y);
            this.icon = icon;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return EncodingTerminalBlockEntity.accepts(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return getContainerSlot() == EncodingTerminalBlockEntity.PAIR ? 1 : super.getMaxStackSize();
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return icon;
        }
    }

    /** A ghost or preview slot: shows an item, never takes or gives one. Some only show in one of the two modes. */
    public static final class FakeSlot extends Slot {
        private final BooleanSupplier active;

        FakeSlot(Container container, int index, int x, int y) {
            this(container, index, x, y, () -> true);
        }

        FakeSlot(Container container, int index, int x, int y, BooleanSupplier active) {
            super(container, index, x, y);
            this.active = active;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean isFake() {
            return true;
        }
    }

    /** Client side: the last message for this screen. */
    @Override
    public Notices.Shown notices() {
        return notices;
    }
}
