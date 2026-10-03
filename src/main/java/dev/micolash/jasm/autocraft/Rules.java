package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.JasmServerData;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs Crafting Deck rules, once a second, for every online player's Crafting Decks. A rule counts only while its
 * Deck is in its player's inventory with the player online, and it never starts a second job while its first is
 * still going. A timer counts only those seconds and never catches up on ones missed. When a rule can't start (no free
 * server, missing ingredients), it tries again a few seconds later.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Rules {
    private static final int EVERY = 20;

    private Rules() {}

    public static List<CraftRule> of(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.DECK_RULES.get(), List.of());
    }

    /** How many rules a Crafting Deck of this tier holds; 0 for normal Decks. */
    public static int limit(ItemStack deck) {
        if (!(deck.getItem() instanceof DeckItem item) || !item.isCrafting()) {
            return 0;
        }
        return switch (item.tier()) {
            case ADVANCED -> 4;
            case ELITE -> 8;
            case ULTIMATE -> 16;
            default -> 0;
        };
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0 || WaferStore.ifOpen(server) == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack deck = inventory.getItem(i);
                if (DeckItem.isCrafting(deck) && !of(deck).isEmpty()) {
                    run(player, deck, server.getTickCount());
                }
            }
        }
    }

    /** One second of one Deck's rules. */
    public static void run(ServerPlayer player, ItemStack deck, long now) {
        // How far each timed rule has counted, when one may try again, and why one is stuck (cleared once it starts or has nothing to do).
        JasmServerData data = JasmServerData.of(player.level().getServer());
        Map<UUID, Integer> counted = data.ruleCounted;
        Map<UUID, Long> retryAt = data.ruleRetryAt;
        Map<UUID, String> stalled = data.ruleStalled;
        var terminal = Jobs.terminalOf(player.level().getServer(), deck);
        if (!DeckItem.worksIn(deck, player.level()) || terminal != null && !DeckItem.worksIn(deck, terminal.getLevel())) {
            of(deck).stream().filter(CraftRule::enabled).forEach(rule -> stalled.put(rule.id(), "message.jasm.deck.dimension_upgrade"));
            return;
        }
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        UUID terminalId = deck.get(JasmComponents.DECK_NETWORK.get());
        if (deckId == null || terminalId == null || !AutocraftState.get(player.level().getServer()).isPaired(terminalId, player.getUUID(), deckId)
                || terminal == null) {
            return;
        }
        List<CraftRule> rules = of(deck);
        for (int r = 0; r < Math.min(rules.size(), limit(deck)); r++) {
            CraftRule rule = rules.get(r);
            if ("message.jasm.deck.dimension_upgrade".equals(stalled.get(rule.id()))) stalled.remove(rule.id());
            if (!rule.enabled() || rule.item().isEmpty()) {
                stalled.remove(rule.id());
                continue;
            }
            if (busy(player.level().getServer(), deckId, rule.id())) {
                continue;
            }
            if (rule.timed()) {
                int seconds = counted.merge(rule.id(), EVERY, Integer::sum);
                if (seconds < rule.seconds() * 20) {
                    continue;
                }
            } else if (have(player, deck, rule) >= rule.threshold()) {
                stalled.remove(rule.id());
                continue;
            }
            if (now < retryAt.getOrDefault(rule.id(), 0L)) {
                continue;
            }
            if (!hasRoom(player, deck, rule)) {
                stalled.put(rule.id(), PauseReason.WAITING_SPACE.key());
                // Nowhere to put the results: a timed rule lets this turn go and counts again; the other waits and looks again.
                if (rule.timed()) {
                    counted.remove(rule.id());
                } else {
                    retryAt.put(rule.id(), now + JasmConfig.RULE_RETRY_SECONDS.getAsInt() * 20L);
                }
                continue;
            }
            String problem = Jobs.start(player, deck, rule.item(), rule.amount(), null, rule.id(), rule.toPlayer());
            if (problem == null) {
                counted.remove(rule.id());
                retryAt.remove(rule.id());
                stalled.remove(rule.id());
            } else {
                stalled.put(rule.id(), problem);
                retryAt.put(rule.id(), now + JasmConfig.RULE_RETRY_SECONDS.getAsInt() * 20L);
            }
        }
    }

    /** How many of the rule's item there are where its results go: on the Deck, or in the player's inventory. */
    static long have(ServerPlayer player, ItemStack deck, CraftRule rule) {
        if (rule.toPlayer()) {
            long total = 0;
            Inventory inventory = player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                if (rule.item().matches(inventory.getItem(i))) {
                    total += inventory.getItem(i).getCount();
                }
            }
            return total;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        DeckStorage.checkAll(store, deck, player);
        return DeckStorage.count(store, deck, rule.item());
    }

    /** Whether the rule's amount fits where its results go. */
    static boolean hasRoom(ServerPlayer player, ItemStack deck, CraftRule rule) {
        if (rule.toPlayer()) {
            long room = 0;
            Inventory inventory = player.getInventory();
            int max = rule.item().getMaxStackSize();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                ItemStack there = inventory.getItem(i);
                if (there.isEmpty()) {
                    room += max;
                } else if (rule.item().matches(there)) {
                    room += Math.max(0, max - there.getCount());
                }
            }
            return room >= rule.amount();
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        return DeckStorage.checked(store, deck, player).room(rule.item(), rule.amount()) >= rule.amount();
    }

    /**
     * This Deck's rules that can't go ahead: the last try failed (no room, missing ingredients, no free server...), or
     * their job's results are waiting for room.
     */
    public static Map<UUID, String> stalled(MinecraftServer server, ItemStack deck) {
        Map<UUID, String> stalled = new HashMap<>();
        Map<UUID, String> known = JasmServerData.of(server).ruleStalled;
        UUID deckId = deck.get(JasmComponents.DECK_ID.get());
        for (CraftRule rule : of(deck)) {
            String why = known.get(rule.id());
            if (rule.enabled() && why != null) {
                stalled.put(rule.id(), why);
            }
        }
        for (AutocraftState.Job job : AutocraftState.get(server).jobs()) {
            if (job.finished() || job.rule().isEmpty() || deckId == null || !job.deck().map(deckId::equals).orElse(false)) {
                continue;
            }
            net.minecraft.server.level.ServerLevel level = server.getLevel(job.server().dimension());
            if (level != null && level.isLoaded(job.server().pos()) && level.getBlockEntity(job.server().pos()) instanceof CraftingServerBlockEntity crafting
                    && crafting.job() != null && crafting.job().id().equals(job.id()) && crafting.job().pause() == PauseReason.WAITING_SPACE) {
                stalled.put(job.rule().get(), PauseReason.WAITING_SPACE.key());
            }
        }
        return stalled;
    }

    /** Whether this rule's last job is still going. */
    static boolean busy(MinecraftServer server, UUID deckId, UUID ruleId) {
        for (AutocraftState.Job job : AutocraftState.get(server).jobs()) {
            if (!job.finished() && job.rule().map(ruleId::equals).orElse(false) && job.deck().map(deckId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
