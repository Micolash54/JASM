package dev.micolash.jasm.dock;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jspecify.annotations.Nullable;

/**
 * The Docking Stations in loaded chunks that hold a Deck, by that Deck's identity. A dock adds itself when it loads or takes a
 * Deck and leaves when it unloads, is broken or gives the Deck back, so finding a docked Deck never looks at the world.
 * Server thread only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DockingStations {
    private static final Map<UUID, DockingStationBlockEntity> BY_DECK = new HashMap<>();
    /** The docks above whose Deck is a Crafting Deck with rules: the only ones the rules look at. */
    private static final Set<DockingStationBlockEntity> WITH_RULES = new LinkedHashSet<>();

    private DockingStations() {}

    /** The dock now holds the Deck {@code deck} (empty when none). Only the first dock to hold a Deck counts for it. */
    static void holds(DockingStationBlockEntity dock, @Nullable UUID previous, ItemStack deck, boolean rules) {
        if (previous != null) BY_DECK.remove(previous, dock);
        WITH_RULES.remove(dock);
        UUID id = deck.isEmpty() ? null : deck.get(JasmComponents.DECK_ID.get());
        if (id == null) return;
        BY_DECK.putIfAbsent(id, dock);
        if (rules && BY_DECK.get(id) == dock) WITH_RULES.add(dock);
    }

    /** The loaded dock holding the Deck with this identity, or null. */
    public static @Nullable DockingStationBlockEntity byDeck(@Nullable UUID deckId) {
        return deckId == null || BY_DECK.isEmpty() ? null : BY_DECK.get(deckId);
    }

    /** Whether the Deck with this identity sits in a loaded dock: routes then use that one, never a carried copy. */
    public static boolean docked(@Nullable UUID deckId) {
        return byDeck(deckId) != null;
    }

    /** Loaded docks holding a Deck of {@code player}'s. */
    public static List<DockingStationBlockEntity> of(UUID player) {
        if (BY_DECK.isEmpty()) return List.of();
        List<DockingStationBlockEntity> found = new ArrayList<>(1);
        for (DockingStationBlockEntity dock : BY_DECK.values()) {
            if (player.equals(dock.deckOwner())) found.add(dock);
        }
        return found;
    }

    /** Docks holding a Crafting Deck with rules, read only. */
    public static Set<DockingStationBlockEntity> withRules() {
        return Collections.unmodifiableSet(WITH_RULES);
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        BY_DECK.clear();
        WITH_RULES.clear();
    }
}
