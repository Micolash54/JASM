package dev.micolash.jasm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out a crafting job before it starts: which items to take from storage, which crafts to run (ingredients
 * first), and what is missing. Knows nothing about Minecraft: items are keys, recipes are {@link Pattern}s.
 *
 * <p>The requested item is always crafted, never taken from storage. Its ingredients come from storage first, then
 * from leftovers of earlier crafts in the same job, and are crafted only when neither has enough. A slot that accepts
 * several items takes them in order of how many there are.
 */
public final class CraftPlanner<K> {
    /** A recipe: what one craft makes, what each slot accepts, and what it leaves behind (empty buckets). */
    public interface Pattern<K> {
        K output();

        long outputCount();

        /** Each used slot: the items it accepts, the encoded one first. */
        List<List<K>> slots();

        /** How many items slot {@code slot} (an index into {@link #slots()}) takes per craft. */
        default long amount(int slot) {
            return 1;
        }

        /** Left behind by one craft, besides the output. */
        Map<K, Long> remainders();
    }

    /** Which patterns make an item; the first is used. */
    public interface Book<K> {
        List<Pattern<K>> patternsFor(K key);
    }

    /** One pattern to run {@code crafts} times. Steps are listed ingredients first. */
    public record Step<K>(Pattern<K> pattern, long crafts) {}

    public enum Problem {
        NONE,
        /** Some ingredient has neither enough in storage nor a pattern. */
        MISSING,
        /** Nothing makes the requested item. */
        NO_PATTERN,
        /** Deeper or larger than the limits allow, or an item needs itself. */
        TOO_COMPLEX
    }

    /**
     * The outcome. {@code taken} is what leaves storage when the job starts; {@code size} is everything taken plus
     * everything made along the way, which must fit in the server.
     */
    public record Plan<K>(Problem problem, Map<K, Long> taken, Map<K, Long> missing, List<Step<K>> steps, long size, long made) {
        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    public static final int MAX_DEPTH = 16;
    public static final int MAX_STEPS = 256;

    private final Book<K> book;
    private final Map<K, Long> stock;
    private final Map<K, Long> taken = new LinkedHashMap<>();
    private final Map<K, Long> missing = new LinkedHashMap<>();
    private final Map<K, Long> spare = new HashMap<>();
    private final List<Step<K>> steps = new ArrayList<>();
    private final Deque<K> making = new ArrayDeque<>();
    private long size;
    private boolean tooComplex;

    private CraftPlanner(Book<K> book, Map<K, Long> stock) {
        this.book = book;
        this.stock = new HashMap<>(stock);
    }

    /** Plans {@code amount} (rounded up to whole crafts) of {@code target} from {@code stock}. */
    public static <K> Plan<K> plan(K target, long amount, Map<K, Long> stock, Book<K> book) {
        CraftPlanner<K> planner = new CraftPlanner<>(book, stock);
        List<Pattern<K>> patterns = book.patternsFor(target);
        if (patterns.isEmpty() || amount <= 0) {
            return new Plan<>(Problem.NO_PATTERN, Map.of(), Map.of(), List.of(), 0, 0);
        }
        Pattern<K> pattern = patterns.getFirst();
        long crafts = ceilDiv(amount, pattern.outputCount());
        planner.making.push(target);
        planner.run(pattern, crafts, 1);
        planner.making.pop();
        long made = crafts * pattern.outputCount();
        Problem problem = planner.tooComplex ? Problem.TOO_COMPLEX : planner.missing.isEmpty() ? Problem.NONE : Problem.MISSING;
        long takenTotal = planner.taken.values().stream().mapToLong(Long::longValue).sum();
        return new Plan<>(problem, planner.taken, planner.missing, List.copyOf(planner.steps), takenTotal + planner.size, made);
    }

    /** Gathers the ingredients for {@code crafts} of {@code pattern}, then adds the step. */
    private void run(Pattern<K> pattern, long crafts, int depth) {
        // Slots that take the same items are supplied together, so their ingredients are crafted in one go.
        Map<List<K>, Long> groups = new LinkedHashMap<>();
        List<List<K>> slots = pattern.slots();
        for (int i = 0; i < slots.size(); i++) {
            groups.merge(slots.get(i), pattern.amount(i), Long::sum);
        }
        for (Map.Entry<List<K>, Long> group : groups.entrySet()) {
            supply(group.getKey(), saturatingMul(crafts, group.getValue()), depth);
            if (tooComplex) {
                return;
            }
        }
        addStep(pattern, crafts);
        if (tooComplex) {
            return;
        }
        size = saturatingAdd(size, saturatingMul(crafts, pattern.outputCount()));
        for (Map.Entry<K, Long> left : pattern.remainders().entrySet()) {
            long amount = saturatingMul(crafts, left.getValue());
            size = saturatingAdd(size, amount);
            spare.merge(left.getKey(), amount, Long::sum);
        }
    }

    /** A pattern already in the list runs more times rather than appearing twice. */
    private void addStep(Pattern<K> pattern, long crafts) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).pattern().equals(pattern)) {
                steps.set(i, new Step<>(pattern, saturatingAdd(steps.get(i).crafts(), crafts)));
                return;
            }
        }
        if (steps.size() >= MAX_STEPS) {
            tooComplex = true;
            return;
        }
        steps.add(new Step<>(pattern, crafts));
    }

    /** {@code needed} items for slots that take {@code options}, from any of them. */
    private void supply(List<K> options, long needed, int depth) {
        if (options.isEmpty()) {
            return;
        }
        // Take what is already there, most plentiful first; ties keep the encoded order.
        List<K> byAmount = new ArrayList<>(options);
        byAmount.sort(Comparator.comparingLong((K k) -> available(k)).reversed());
        for (K option : byAmount) {
            if (needed <= 0) {
                return;
            }
            needed -= use(option, needed);
        }
        if (needed <= 0) {
            return;
        }
        for (K option : options) {
            List<Pattern<K>> patterns = book.patternsFor(option);
            if (!patterns.isEmpty()) {
                craft(option, patterns.getFirst(), needed, depth);
                return;
            }
        }
        missing.merge(options.getFirst(), needed, Long::sum);
    }

    private void craft(K key, Pattern<K> pattern, long needed, int depth) {
        if (depth >= MAX_DEPTH || making.contains(key)) {
            tooComplex = true;
            return;
        }
        long crafts = ceilDiv(needed, pattern.outputCount());
        making.push(key);
        run(pattern, crafts, depth + 1);
        making.pop();
        long extra = saturatingMul(crafts, pattern.outputCount()) - needed;
        if (extra > 0) {
            spare.merge(key, extra, Long::sum);
        }
    }

    private long available(K key) {
        return spare.getOrDefault(key, 0L) + stock.getOrDefault(key, 0L);
    }

    /** Takes up to {@code amount}: leftovers from this job first, then storage. Returns how many. */
    private long use(K key, long amount) {
        long fromSpare = Math.min(amount, spare.getOrDefault(key, 0L));
        if (fromSpare > 0) {
            spare.merge(key, -fromSpare, Long::sum);
        }
        long fromStock = Math.min(amount - fromSpare, stock.getOrDefault(key, 0L));
        if (fromStock > 0) {
            stock.merge(key, -fromStock, Long::sum);
            taken.merge(key, fromStock, Long::sum);
        }
        return fromSpare + fromStock;
    }

    private static long ceilDiv(long a, long b) {
        return b <= 0 ? a : (a + b - 1) / b;
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    private static long saturatingMul(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        return (hi == 0 && lo >= 0) ? lo : Long.MAX_VALUE;
    }
}
