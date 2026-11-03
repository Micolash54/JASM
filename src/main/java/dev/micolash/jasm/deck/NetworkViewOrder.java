package dev.micolash.jasm.deck;

import dev.micolash.jasm.config.JasmClientConfig.NetworkSort;
import dev.micolash.jasm.deck.NetworkViewPayloads.Category;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The order of the Network tab's list. By power use: whatever adds power first, biggest first, then whatever uses it,
 * biggest first, then whatever does neither; Batteries by what they hold. By name: A to Z. By machine type: blocks of a
 * kind together, the categories in their usual order.
 */
public final class NetworkViewOrder {
    private NetworkViewOrder() {}

    /** A category's header ({@code node} null) or one row. */
    public record Item(Category category, NetworkViewPayloads.@Nullable Node node) {}

    /** The list: grouped under a header per category with rows (folded ones show only their header), or one flat list. */
    public static List<Item> list(NetworkViewPayloads.View view, boolean grouped, NetworkSort sort, Set<Category> folded) {
        Map<Category, List<NetworkViewPayloads.Node>> rows = byCategory(view, sort);
        List<Item> order = new ArrayList<>();
        if (grouped) {
            for (Category category : categories(view.header(), sort)) {
                order.add(new Item(category, null));
                if (folded.contains(category)) continue;
                for (NetworkViewPayloads.Node node : rows.getOrDefault(category, List.of())) order.add(new Item(category, node));
            }
        } else {
            List<NetworkViewPayloads.Node> nodes = new ArrayList<>(view.nodes());
            nodes.sort(rows(sort));
            for (NetworkViewPayloads.Node node : nodes) order.add(new Item(node.category(), node));
        }
        return order;
    }

    /** Each category's rows, in the order they are shown. */
    public static Map<Category, List<NetworkViewPayloads.Node>> byCategory(NetworkViewPayloads.View view, NetworkSort sort) {
        List<NetworkViewPayloads.Node> nodes = new ArrayList<>(view.nodes());
        nodes.sort(rows(sort));
        Map<Category, List<NetworkViewPayloads.Node>> found = new EnumMap<>(Category.class);
        for (NetworkViewPayloads.Node node : nodes) found.computeIfAbsent(node.category(), c -> new ArrayList<>()).add(node);
        return found;
    }

    /** The categories that have anything in them, in the order they are shown. */
    public static List<Category> categories(NetworkViewPayloads.Header header, NetworkSort sort) {
        List<Category> found = new ArrayList<>();
        for (Category category : Category.values()) {
            if (header.group(category).count() > 0) found.add(category);
        }
        found.sort(switch (sort) {
            case POWER -> Comparator.<Category>comparingInt(c -> rank(header.group(c).power()))
                    .thenComparingLong(c -> -Math.abs(header.group(c).power()))
                    .thenComparingInt(Category::ordinal);
            case NAME -> Comparator.comparing(c -> name(c).getString().toLowerCase(Locale.ROOT));
            case TYPE -> Comparator.comparingInt(Category::ordinal);
        });
        return found;
    }

    public static Comparator<NetworkViewPayloads.Node> rows(NetworkSort sort) {
        Comparator<NetworkViewPayloads.Node> byName = Comparator.comparing(n -> n.name().getString().toLowerCase(Locale.ROOT));
        Comparator<NetworkViewPayloads.Node> byPlace = Comparator.<NetworkViewPayloads.Node, String>comparing(n -> n.dimension().identifier().toString())
                .thenComparingLong(n -> n.pos().asLong());
        return switch (sort) {
            case POWER -> Comparator.<NetworkViewPayloads.Node>comparingInt(n -> rank(n.power()))
                    .thenComparingLong(n -> -Math.abs((long) n.power()))
                    .thenComparingLong(n -> -n.stored())
                    .thenComparing(byName).thenComparing(byPlace);
            case NAME -> byName.thenComparing(byPlace);
            case TYPE -> Comparator.<NetworkViewPayloads.Node, String>comparing(n -> BuiltInRegistries.ITEM.getKey(n.icon().getItem()).toString())
                    .thenComparing(byName).thenComparing(byPlace);
        };
    }

    /** Adds power first, then uses it, then neither. */
    private static int rank(long power) {
        return power > 0 ? 0 : power < 0 ? 1 : 2;
    }

    public static Component name(Category category) {
        return Component.translatable("screen.jasm.network.category." + category.key());
    }
}
