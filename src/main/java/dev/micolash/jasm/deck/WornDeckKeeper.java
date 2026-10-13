package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a worn Deck from being lost when a world is opened without Curios. The game drops the saved data of a mod
 * that isn't there, worn slot and all, so JASM writes its own copy of the worn Decks into the same player file, in the
 * same save, read straight from the slot. With Curios there, that copy is never used. Without it, the copy is the
 * only one left: the Decks go back into the inventory (or onto the ground at the player when it is full) and the file
 * is saved at once, so the next load can't give them back again.
 *
 * <p>Each Deck carries a mark of its own while worn, and a Deck given back gets a new one. The marks of the copies given
 * back are remembered, so if Curios ever comes back with its old copy of the slot, that copy is told apart from the
 * Deck the player already has, and taken out.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class WornDeckKeeper {
    /** Marks of Decks given back that are remembered per player, the oldest dropped first. */
    static final int MAX_GIVEN_BACK = 16;

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Jasm.MODID);
    public static final Supplier<AttachmentType<Kept>> KEPT = ATTACHMENTS.register("worn_decks",
            () -> AttachmentType.builder(Kept::new).serialize(new Serializer()).copyHandler(Kept::copy).copyOnDeath().build());

    private WornDeckKeeper() {}

    /** What JASM keeps on a player about the worn slot. */
    public static final class Kept {
        private final @Nullable Player owner;
        /** Read from the file while Curios is missing: the Decks worn at the last save, waiting to be given back. */
        final List<ItemStack> waiting = new ArrayList<>();
        /** The marks the Decks given back wore before, the newest last. */
        final List<UUID> givenBack = new ArrayList<>();

        Kept(IAttachmentHolder holder) {
            this.owner = holder instanceof Player player ? player : null;
        }

        private Kept copy(IAttachmentHolder holder, net.minecraft.core.HolderLookup.Provider provider) {
            Kept copy = new Kept(holder);
            waiting.forEach(stack -> copy.waiting.add(stack.copy()));
            copy.givenBack.addAll(givenBack);
            return copy;
        }
    }

    /**
     * With Curios, writes the Decks worn right now; without it, the ones still waiting. Nothing is written when there is
     * nothing to keep.
     */
    private static final class Serializer implements IAttachmentSerializer<Kept> {
        @Override
        public Kept read(IAttachmentHolder holder, ValueInput input) {
            Kept kept = new Kept(holder);
            // With Curios there, its slot is what counts; the copy only matters once Curios is gone.
            if (!WornDecks.available()) {
                input.listOrEmpty("worn", ItemStack.CODEC).stream().forEach(kept.waiting::add);
            }
            input.listOrEmpty("given_back", UUIDUtil.CODEC).stream().forEach(kept.givenBack::add);
            return kept;
        }

        @Override
        public boolean write(Kept kept, ValueOutput output) {
            List<ItemStack> worn = new ArrayList<>();
            if (WornDecks.available()) {
                if (kept.owner != null) WornDecks.forEach(kept.owner, deck -> worn.add(deck.copy()));
            } else {
                worn.addAll(kept.waiting);
            }
            if (worn.isEmpty() && kept.givenBack.isEmpty()) {
                return false;
            }
            var wornList = output.list("worn", ItemStack.CODEC);
            worn.forEach(wornList::add);
            var givenBackList = output.list("given_back", UUIDUtil.CODEC);
            kept.givenBack.forEach(givenBackList::add);
            return true;
        }
    }

    /** Every Deck waiting to be given back: its wafers are still the player's, saved in their file. */
    public static void forEachWaiting(Player player, Consumer<ItemStack> action) {
        Kept kept = player.getExistingDataOrNull(KEPT);
        if (kept != null) {
            kept.waiting.forEach(action);
        }
    }

    /**
     * Called for each worn Deck every tick: gives it its mark. An old copy keeps its old mark; the player tick takes it
     * out, outside Curios' own pass over the slot.
     */
    public static void wornTick(Player player, ItemStack deck) {
        if (player.level().isClientSide() || !DeckItem.isDeck(deck) || isOldCopy(player, deck)) {
            return;
        }
        if (!deck.has(JasmComponents.WORN_MARK.get())) {
            deck.set(JasmComponents.WORN_MARK.get(), UUID.randomUUID());
        }
    }

    /** Whether this worn Deck is Curios' old copy of a Deck that was already given back. */
    public static boolean isOldCopy(Player player, ItemStack deck) {
        Kept kept = player.getExistingDataOrNull(KEPT);
        UUID mark = deck.get(JasmComponents.WORN_MARK.get());
        return kept != null && mark != null && DeckItem.isDeck(deck) && kept.givenBack.contains(mark);
    }

    private static boolean takeOutIfOldCopy(Player player, int index, ItemStack deck) {
        if (!isOldCopy(player, deck)) {
            return false;
        }
        Kept kept = player.getExistingDataOrNull(KEPT);
        kept.givenBack.remove(deck.get(JasmComponents.WORN_MARK.get()));
        WornDecks.set(player, index, ItemStack.EMPTY);
        Jasm.LOGGER.info("Took an old copy of a Deck out of {}'s Deck slot: it was given back while Curios was missing",
                player.getPlainTextName());
        return true;
    }

    /** Takes every old copy out of the worn slot. Returns how many there were. */
    public static int takeOutOldCopies(Player player) {
        Kept kept = player.getExistingDataOrNull(KEPT);
        if (kept == null || kept.givenBack.isEmpty() || !WornDecks.available()) {
            return 0;
        }
        int removed = 0;
        for (int i = 0; i < WornDecks.size(player); i++) {
            if (takeOutIfOldCopy(player, i, WornDecks.get(player, i))) removed++;
        }
        return removed;
    }

    /**
     * Without Curios, gives back the Decks that were worn at the last save, each with a new mark. Returns how many.
     * Waits while the player is dead: a dead player's inventory is gone by the time they respawn.
     */
    public static int giveBack(ServerPlayer player) {
        Kept kept = player.getExistingDataOrNull(KEPT);
        if (WornDecks.available() || !player.isAlive() || kept == null || kept.waiting.isEmpty()) {
            return 0;
        }
        List<ItemStack> decks = List.copyOf(kept.waiting);
        kept.waiting.clear();
        for (ItemStack worn : decks) {
            UUID old = worn.get(JasmComponents.WORN_MARK.get());
            if (old != null) kept.givenBack.add(old);
            ItemStack back = worn.copy();
            back.set(JasmComponents.WORN_MARK.get(), UUID.randomUUID());
            if (!player.getInventory().add(back)) {
                ItemEntity dropped = player.drop(back, false, false);
                if (dropped != null) dropped.setUnlimitedLifetime();
            }
        }
        while (kept.givenBack.size() > MAX_GIVEN_BACK) {
            kept.givenBack.removeFirst();
        }
        Jasm.LOGGER.info("Gave {} back the {} Deck(s) they wore in the Deck slot: Curios is not installed", player.getPlainTextName(), decks.size());
        // Saved now: if the server stopped before its next save, the old file would give the Decks back a second time.
        player.level().getServer().getPlayerList().getPlayerIo().save(player);
        return decks.size();
    }

    /** A player joined: with Curios, old copies leave the slot; without it, worn Decks come back. */
    public static void joined(ServerPlayer player) {
        // Makes sure the worn slot is written into every save from now on.
        player.getData(KEPT);
        takeOutOldCopies(player);
        giveBack(player);
    }

    @SubscribeEvent
    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            joined(player);
        }
    }

    /** A player respawned: Decks still waiting (they were dead when they joined) come back now. */
    public static void respawned(ServerPlayer player) {
        player.getData(KEPT);
        giveBack(player);
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            respawned(player);
        }
    }

    /** Only players with a Deck given back are looked at, and only while Curios is there. */
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && WornDecks.available()) {
            Kept kept = player.getExistingDataOrNull(KEPT);
            if (kept != null && !kept.givenBack.isEmpty()) {
                takeOutOldCopies(player);
            }
        }
    }
}
