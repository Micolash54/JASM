package dev.micolash.jasm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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

        /** How much room {@code amount} of {@code key} takes in a server; a bucket of fluid takes as much as one item. */
        default long space(K key, long amount) {
            return amount;
        }
    }

    /** One pattern to run {@code crafts} times. Steps are listed ingredients first. */
    public record Step<K>(Pattern<K> pattern, long crafts) {}

    /** One craft taking from a box: another step, or storage or the missing list. Only one of {@code from} and {@code kind} is used. */
    private record Taking<K>(Pattern<K> consumer, @Nullable Pattern<K> from, @Nullable Kind kind, @Nullable K key) {}

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
    public record Plan<K>(Problem problem, Map<K, Long> taken, Map<K, Long> missing, List<Step<K>> steps, long size, long made, Tree<K> tree) {
        public Plan(Problem problem, Map<K, Long> taken, Map<K, Long> missing, List<Step<K>> steps, long size, long made) {
            this(problem, taken, missing, steps, size, made, Tree.empty());
        }

        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    /** Sent to the screen as its position: keep the order. */
    public enum Kind {
        /** Made from other boxes. */
        CRAFT,
        /** Taken from storage. */
        STORAGE,
        /** Neither in storage nor craftable. */
        MISSING
    }

    /**
     * One box of the tree. A craft's {@code amount} is everything it makes over {@code crafts} runs; a storage or
     * missing box's {@code amount} is how many are taken or lacking.
     */
    public record Box<K>(Kind kind, K key, long amount, long crafts) {}

    /** Box {@code from} (a craft) takes from box {@code to}. */
    public record Link(int from, int to) {}

    /**
     * Which craft feeds which, for drawing: {@code root} is the box of the requested item. A shared part is one box
     * with a link from each craft that uses it. Empty unless the plan was asked to record it, or when it cannot be drawn.
     */
    public record Tree<K>(List<Box<K>> boxes, List<Link> links, int root) {
        private static final Tree<?> EMPTY = new Tree<>(List.of(), List.of(), -1);

        @SuppressWarnings("unchecked")
        public static <K> Tree<K> empty() {
            return (Tree<K>) EMPTY;
        }
    }

    public static final int MAX_DEPTH = 16;
    public static final int MAX_STEPS = 256;

    private final Book<K> book;
    /** Crafts the server runs at once: how many copies of an item that comes back are worth taking. */
    private final long lanes;
    private final Map<K, Long> stock;
    private final Map<K, Long> taken = new LinkedHashMap<>();
    private final Map<K, Long> missing = new LinkedHashMap<>();
    private final Map<K, Long> spare = new HashMap<>();
    private final List<Step<K>> steps = new ArrayList<>();
    private final Deque<K> making = new ArrayDeque<>();
    /** Who takes from whom, in the order it happened; null when the plan is not recording. */
    private final @Nullable List<Taking<K>> takings;
    /** The step that last made each item, or left it behind: where an item reused from leftovers came from. */
    private final Map<K, Pattern<K>> madeBy = new HashMap<>();
    private long size;
    private boolean tooComplex;

    private CraftPlanner(Book<K> book, Map<K, Long> stock, boolean record, long lanes) {
        this.book = book;
        this.stock = new HashMap<>(stock);
        this.takings = record ? new ArrayList<>() : null;
        this.lanes = Math.max(1, lanes);
    }

    /** Plans {@code amount} (rounded up to whole crafts) of {@code target} from {@code stock}. */
    public static <K> Plan<K> plan(K target, long amount, Map<K, Long> stock, Book<K> book) {
        return plan(target, amount, stock, book, false);
    }

    /** As above; with {@code tree} the plan also records which craft feeds which. */
    public static <K> Plan<K> plan(K target, long amount, Map<K, Long> stock, Book<K> book, boolean tree) {
        return plan(target, amount, stock, book, tree, 1);
    }

    /**
     * As above, for a server that runs {@code lanes} crafts at once: an item a craft hands back is taken once for each
     * craft that can run at the same time, as far as there are copies to spare. Only the first copy is ever required.
     */
    public static <K> Plan<K> plan(K target, long amount, Map<K, Long> stock, Book<K> book, boolean tree, long lanes) {
        CraftPlanner<K> planner = new CraftPlanner<>(book, stock, tree, lanes);
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
        long takenTotal = 0;
        for (Map.Entry<K, Long> entry : planner.taken.entrySet()) {
            takenTotal = saturatingAdd(takenTotal, book.space(entry.getKey(), entry.getValue()));
        }
        return new Plan<>(problem, planner.taken, planner.missing, List.copyOf(planner.steps), saturatingAdd(takenTotal, planner.size), made,
                planner.tooComplex ? Tree.empty() : planner.tree(pattern));
    }

    /** Gathers the ingredients for {@code crafts} of {@code pattern}, then adds the step. */
    private void run(Pattern<K> pattern, long crafts, int depth) {
        // Slots that take the same items are supplied together, so their ingredients are crafted in one go.
        Map<List<K>, Long> groups = new LinkedHashMap<>();
        List<List<K>> slots = pattern.slots();
        for (int i = 0; i < slots.size(); i++) {
            groups.merge(slots.get(i), pattern.amount(i), Long::sum);
        }
        // What each craft hands back is used again by the next one, so a mold that returns is needed once, not every time.
        Map<K, Long> reused = new HashMap<>();
        // Extra copies of what comes back, so that more crafts can run at once: wanted, never required.
        Map<List<K>, Long> extras = new LinkedHashMap<>();
        Map<List<K>, K> extraKeys = new HashMap<>();
        for (Map.Entry<List<K>, Long> group : groups.entrySet()) {
            long needed = saturatingMul(crafts, group.getValue());
            K key = crafts > 1 ? group.getKey().stream().filter(pattern.remainders()::containsKey).findFirst().orElse(null) : null;
            if (key != null) {
                long back = pattern.remainders().get(key);
                if (back > 0) {
                    long firstCraft = group.getValue();
                    long later = saturatingMul(crafts - 1, Math.max(0, group.getValue() - back));
                    long once = Math.min(needed, saturatingAdd(firstCraft, later));
                    reused.merge(key, needed - once, Long::sum);
                    needed = once;
                    long extra = saturatingMul(Math.min(crafts, lanes) - 1, Math.min(back, group.getValue()));
                    if (extra > 0) {
                        extras.put(group.getKey(), extra);
                        extraKeys.put(group.getKey(), key);
                    }
                }
            }
            supply(group.getKey(), needed, depth, pattern);
            if (tooComplex) {
                return;
            }
        }
        // Only once every slot has what it needs, so an extra copy never takes what another slot required.
        for (Map.Entry<List<K>, Long> extra : extras.entrySet()) {
            long lent = useAvailable(extra.getKey(), extra.getValue(), pattern);
            // They come back after the step like the first copy.
            reused.merge(extraKeys.get(extra.getKey()), -lent, Long::sum);
        }
        addStep(pattern, crafts);
        if (tooComplex) {
            return;
        }
        size = saturatingAdd(size, book.space(pattern.output(), saturatingMul(crafts, pattern.outputCount())));
        madeBy.put(pattern.output(), pattern);
        for (Map.Entry<K, Long> left : pattern.remainders().entrySet()) {
            long amount = Math.max(0, saturatingMul(crafts, left.getValue()) - reused.getOrDefault(left.getKey(), 0L));
            size = saturatingAdd(size, book.space(left.getKey(), amount));
            spare.merge(left.getKey(), amount, Long::sum);
            if (amount > 0) {
                madeBy.put(left.getKey(), pattern);
            }
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

    /** {@code needed} items for slots that take {@code options}, from any of them, for the craft {@code consumer}. */
    private void supply(List<K> options, long needed, int depth, Pattern<K> consumer) {
        if (options.isEmpty()) {
            return;
        }
        needed -= useAvailable(options, needed, consumer);
        if (needed <= 0) {
            return;
        }
        for (K option : options) {
            List<Pattern<K>> patterns = book.patternsFor(option);
            if (!patterns.isEmpty()) {
                craft(option, patterns.getFirst(), needed, depth, consumer);
                return;
            }
        }
        missing.merge(options.getFirst(), needed, Long::sum);
        take(new Taking<>(consumer, null, Kind.MISSING, options.getFirst()));
    }

    /** Takes up to {@code amount} of what is already there for {@code options}, most plentiful first; ties keep the encoded order. Returns how many. */
    private long useAvailable(List<K> options, long amount, Pattern<K> consumer) {
        List<K> byAmount = new ArrayList<>(options);
        byAmount.sort(Comparator.comparingLong((K k) -> available(k)).reversed());
        long got = 0;
        for (K option : byAmount) {
            if (got >= amount) {
                break;
            }
            got += use(option, amount - got, consumer);
        }
        return got;
    }

    private void craft(K key, Pattern<K> pattern, long needed, int depth, Pattern<K> consumer) {
        if (depth >= MAX_DEPTH || making.contains(key)) {
            tooComplex = true;
            return;
        }
        long crafts = ceilDiv(needed, pattern.outputCount());
        making.push(key);
        run(pattern, crafts, depth + 1);
        making.pop();
        take(new Taking<>(consumer, pattern, null, null));
        long extra = saturatingMul(crafts, pattern.outputCount()) - needed;
        if (extra > 0) {
            spare.merge(key, extra, Long::sum);
        }
    }

    private long available(K key) {
        return spare.getOrDefault(key, 0L) + stock.getOrDefault(key, 0L);
    }

    /** Takes up to {@code amount}: leftovers from this job first, then storage. Returns how many. */
    private long use(K key, long amount, Pattern<K> consumer) {
        long fromSpare = Math.min(amount, spare.getOrDefault(key, 0L));
        if (fromSpare > 0) {
            spare.merge(key, -fromSpare, Long::sum);
            Pattern<K> maker = madeBy.get(key);
            if (maker != null) {
                take(new Taking<>(consumer, maker, null, null));
            }
        }
        long fromStock = Math.min(amount - fromSpare, stock.getOrDefault(key, 0L));
        if (fromStock > 0) {
            stock.merge(key, -fromStock, Long::sum);
            taken.merge(key, fromStock, Long::sum);
            take(new Taking<>(consumer, null, Kind.STORAGE, key));
        }
        return fromSpare + fromStock;
    }

    private void take(Taking<K> taking) {
        if (takings != null) {
            takings.add(taking);
        }
    }

    /** The tree of the finished plan; {@code rootPattern} is the requested item's pattern (its step is the last). */
    private Tree<K> tree(Pattern<K> rootPattern) {
        if (takings == null) {
            return Tree.empty();
        }
        List<Box<K>> boxes = new ArrayList<>();
        Map<Pattern<K>, Integer> stepBox = new HashMap<>();
        for (Step<K> step : steps) {
            stepBox.put(step.pattern(), boxes.size());
            boxes.add(new Box<>(Kind.CRAFT, step.pattern().output(), saturatingMul(step.crafts(), step.pattern().outputCount()), step.crafts()));
        }
        Map<K, Integer> storageBox = new HashMap<>();
        for (Map.Entry<K, Long> entry : taken.entrySet()) {
            storageBox.put(entry.getKey(), boxes.size());
            boxes.add(new Box<>(Kind.STORAGE, entry.getKey(), entry.getValue(), 0));
        }
        Map<K, Integer> missingBox = new HashMap<>();
        for (Map.Entry<K, Long> entry : missing.entrySet()) {
            missingBox.put(entry.getKey(), boxes.size());
            boxes.add(new Box<>(Kind.MISSING, entry.getKey(), entry.getValue(), 0));
        }
        Set<Link> links = new LinkedHashSet<>();
        for (Taking<K> taking : takings) {
            Integer from = stepBox.get(taking.consumer());
            Integer to = taking.from() != null
                    ? stepBox.get(taking.from())
                    : taking.kind() == Kind.STORAGE ? storageBox.get(taking.key()) : missingBox.get(taking.key());
            if (from != null && to != null && !from.equals(to)) {
                links.add(new Link(from, to));
            }
        }
        Integer root = stepBox.get(rootPattern);
        return new Tree<>(List.copyOf(boxes), List.copyOf(links), root == null ? -1 : root);
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
