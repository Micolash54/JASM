package dev.micolash.jasm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Works out a crafting job before it starts: which items to take from storage, which crafts to run (ingredients
 * first), and what is missing. Knows nothing about Minecraft: items are keys, recipes are {@link Pattern}s.
 *
 * <p>The requested item is always crafted, never taken from storage. Its ingredients come from storage first, then
 * from leftovers of earlier crafts in the same job, and are crafted only when neither has enough. A slot that accepts
 * several items takes the encoded one first, then the others in order of how many there are.
 *
 * <p>A pattern whose own item in a slot is also what it makes (a smithing template copied with diamonds) grows: it
 * needs a seed, the copies one craft takes, which the job holds back for itself and gives back at the end. Each craft
 * then adds what it makes beyond what it took. Storage always keeps a seed of such an item: other crafts never take it.
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

        /**
         * Whether one craft may mix the items a slot accepts. When not (a machine slot that holds one kind), each craft
         * gets all its share of a slot as one item.
         */
        default boolean mixes() {
            return true;
        }
    }

    /** Which patterns make an item; the first is used. */
    public interface Book<K> {
        List<Pattern<K>> patternsFor(K key);

        /** How much room {@code amount} of {@code key} takes in a server; a bucket of fluid takes as much as one item. */
        default long space(K key, long amount) {
            return amount;
        }

        /** Whether copies of {@code key} may be made from copies of itself. */
        default boolean grows(K key) {
            return true;
        }
    }

    /**
     * One pattern to run {@code crafts} times, and every item it was planned to use over all of them, made in the job
     * or taken from storage. Steps are listed ingredients first. {@code made} is what the step adds: for a growing
     * pattern only what it makes beyond what it takes. {@code rounds} is how many rounds a growing pattern needs, each
     * starting only with copies already made; 0 for any other.
     */
    public record Step<K>(Pattern<K> pattern, long crafts, Map<K, Long> uses, long made, long rounds) {
        public Step(Pattern<K> pattern, long crafts) {
            this(pattern, crafts, Map.of());
        }

        public Step(Pattern<K> pattern, long crafts, Map<K, Long> uses) {
            this(pattern, crafts, uses, saturatingMul(crafts, pattern.outputCount()), 0);
        }
    }

    /** A growing pattern: how many copies of its own item one craft takes, and how many it gains. */
    private record Growth(long seed, long gain) {}

    /** One craft taking from a box: another step, or storage or the missing list. Only one of {@code from} and {@code kind} is used. */
    private record Taking<K>(Pattern<K> consumer, @Nullable Pattern<K> from, @Nullable Kind kind, @Nullable K key) {}

    public enum Problem {
        NONE,
        /** Some ingredient has neither enough in storage nor a pattern. */
        MISSING,
        /** Nothing makes the requested item. */
        NO_PATTERN,
        /** Deeper or larger than the limits allow, or an item needs itself. */
        TOO_COMPLEX,
        /** A pattern that takes its own item makes no more of it than it takes. */
        NO_GAIN,
        /** A pattern that takes its own item has no copy to start from, and nothing else makes one. */
        NO_SEED
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

        /** Rounds the growing steps need, added up; 0 when none grows. */
        public long rounds() {
            long rounds = 0;
            for (Step<K> step : steps) {
                rounds = saturatingAdd(rounds, step.rounds());
            }
            return rounds;
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
    /** What each pattern was given, so a job can keep each step to its own share. */
    private final Map<Pattern<K>, Map<K, Long>> uses = new HashMap<>();
    /** Copies a growing item's step starts from, held back by the job and given back at its end. */
    private final Map<K, Long> held = new HashMap<>();
    /** How many of those copies the job made itself, with another pattern. */
    private final Map<K, Long> seedsMade = new HashMap<>();
    /** The pattern used for each item, once worked out; empty when none can be used. */
    private final Map<K, Optional<Pattern<K>>> mains = new HashMap<>();
    private long size;
    private boolean tooComplex;
    private boolean noGain;
    private boolean noSeed;

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
        Pattern<K> pattern = planner.main(target);
        if (pattern == null) {
            return new Plan<>(planner.unusable(target), Map.of(), Map.of(), List.of(), 0, 0);
        }
        Growth growth = planner.growth(pattern);
        Pattern<K> root = pattern;
        long made;
        if (growth == null) {
            long crafts = ceilDiv(amount, pattern.outputCount());
            planner.making.push(target);
            planner.run(pattern, crafts, 1);
            planner.making.pop();
            made = saturatingMul(crafts, pattern.outputCount());
        } else {
            // Asked for N, the job makes N more than there were: a seed it had to make counts toward them.
            planner.hold(target, growth.seed(), 1, pattern);
            long seedMade = planner.seedsMade.getOrDefault(target, 0L);
            long crafts = ceilDiv(Math.max(0, amount - seedMade), growth.gain());
            if (crafts > 0 && !planner.tooComplex) {
                planner.making.push(target);
                planner.run(pattern, crafts, 1);
                planner.making.pop();
            } else if (!planner.steps.isEmpty()) {
                root = planner.steps.getLast().pattern();
            }
            made = saturatingAdd(seedMade, saturatingMul(crafts, growth.gain()));
        }
        Problem problem = planner.tooComplex ? Problem.TOO_COMPLEX
                : planner.noGain ? Problem.NO_GAIN
                : planner.noSeed ? Problem.NO_SEED
                : planner.missing.isEmpty() ? Problem.NONE : Problem.MISSING;
        long takenTotal = 0;
        for (Map.Entry<K, Long> entry : planner.taken.entrySet()) {
            takenTotal = saturatingAdd(takenTotal, book.space(entry.getKey(), entry.getValue()));
        }
        List<Step<K>> steps = new ArrayList<>();
        for (Step<K> step : planner.steps) {
            Map<K, Long> used = planner.uses.get(step.pattern());
            Map<K, Long> share = used == null ? Map.of() : Collections.unmodifiableMap(used);
            Growth grows = planner.growth(step.pattern());
            steps.add(grows == null ? new Step<>(step.pattern(), step.crafts(), share)
                    : new Step<>(step.pattern(), step.crafts(), share, saturatingMul(step.crafts(), grows.gain()),
                            rounds(grows, step.crafts())));
        }
        return new Plan<>(problem, planner.taken, planner.missing, List.copyOf(steps), saturatingAdd(takenTotal, planner.size), made,
                planner.tooComplex ? Tree.empty() : planner.tree(root));
    }

    /** The most rounds worked out one by one; a step past it says this many. */
    static final long MAX_ROUNDS = 1_000_000;

    /**
     * Rounds {@code crafts} of a growing pattern need, starting from one seed: each round runs as many crafts as the
     * copies already there allow, and what they make joins the next round.
     */
    private static long rounds(Growth growth, long crafts) {
        long copies = growth.seed();
        long left = crafts;
        long rounds = 0;
        while (left > 0 && rounds < MAX_ROUNDS) {
            long now = Math.min(left, copies / growth.seed());
            left -= now;
            copies = saturatingAdd(copies, saturatingMul(now, growth.gain()));
            rounds++;
        }
        return rounds;
    }

    /**
     * The pattern used to make {@code key}: the first one, skipping those that take {@code key} itself but can't gain
     * from it. Null when none is left.
     */
    private @Nullable Pattern<K> main(K key) {
        return mains.computeIfAbsent(key, k -> {
            for (Pattern<K> pattern : book.patternsFor(k)) {
                Growth self = selfTaking(pattern);
                if (self == null || self.gain() > 0) {
                    return Optional.of(pattern);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Why the patterns for {@code key} can't be used, when {@link #main} found none. */
    private Problem unusable(K key) {
        for (Pattern<K> pattern : book.patternsFor(key)) {
            Growth self = selfTaking(pattern);
            if (self != null && self.gain() <= 0) {
                return Problem.NO_GAIN;
            }
        }
        return book.patternsFor(key).isEmpty() ? Problem.NO_PATTERN : Problem.TOO_COMPLEX;
    }

    /**
     * How a pattern grows, or null when it doesn't: no slot's own item is what it makes, or the book says what it makes
     * can't grow (such a pattern takes its own item from storage like any other ingredient).
     */
    private @Nullable Growth growth(Pattern<K> pattern) {
        return book.grows(pattern.output()) ? selfTaking(pattern) : null;
    }

    /** How many of what a pattern makes its own slots take, and how many it gains; null when they take none. */
    private @Nullable Growth selfTaking(Pattern<K> pattern) {
        K output = pattern.output();
        long takes = 0;
        List<List<K>> slots = pattern.slots();
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).isEmpty() && slots.get(i).getFirst().equals(output)) {
                takes = saturatingAdd(takes, pattern.amount(i));
            }
        }
        if (takes <= 0) {
            return null;
        }
        long gives = saturatingAdd(pattern.outputCount(), pattern.remainders().getOrDefault(output, 0L));
        return new Growth(takes, gives - takes);
    }

    /** A pattern with no slot taking what it makes, to make the first copies of {@code key}; null when there is none. */
    private @Nullable Pattern<K> seedPattern(K key) {
        for (Pattern<K> pattern : book.patternsFor(key)) {
            if (selfTaking(pattern) == null) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * Holds back {@code seed} copies of {@code key} for the growing pattern {@code grower}: from this job's leftovers,
     * then storage, then made with another pattern. With none of those, the plan has no seed and the copies are missing.
     */
    private void hold(K key, long seed, int depth, Pattern<K> grower) {
        long want = seed - held.getOrDefault(key, 0L);
        if (want <= 0) {
            return;
        }
        long fromSpare = Math.min(want, spare.getOrDefault(key, 0L));
        if (fromSpare > 0) {
            spare.merge(key, -fromSpare, Long::sum);
            Pattern<K> maker = madeBy.get(key);
            if (maker != null) {
                take(new Taking<>(grower, maker, null, null));
            }
        }
        long fromStock = Math.min(want - fromSpare, stock.getOrDefault(key, 0L));
        if (fromStock > 0) {
            stock.merge(key, -fromStock, Long::sum);
            taken.merge(key, fromStock, Long::sum);
            take(new Taking<>(grower, null, Kind.STORAGE, key));
        }
        held.merge(key, fromSpare + fromStock, Long::sum);
        long lacking = want - fromSpare - fromStock;
        if (lacking <= 0) {
            return;
        }
        Pattern<K> other = seedPattern(key);
        if (other == null) {
            noSeed = true;
            missing.merge(key, lacking, Long::sum);
            take(new Taking<>(grower, null, Kind.MISSING, key));
            return;
        }
        // craft() puts only what is over among the leftovers: the copies asked for are the seed.
        craft(key, other, lacking, depth, grower);
        held.merge(key, lacking, Long::sum);
        seedsMade.merge(key, lacking, Long::sum);
    }

    /** Gathers the ingredients for {@code crafts} of {@code pattern}, then adds the step. */
    private void run(Pattern<K> pattern, long crafts, int depth) {
        K output = pattern.output();
        Growth growth = growth(pattern);
        // Slots that take the same items are supplied together, so their ingredients are crafted in one go.
        Map<List<K>, Long> groups = new LinkedHashMap<>();
        List<List<K>> slots = pattern.slots();
        for (int i = 0; i < slots.size(); i++) {
            List<K> options = slots.get(i);
            if (growth != null && !options.isEmpty() && options.getFirst().equals(output)) {
                // fed by the seed and the copies the step makes itself
                continue;
            }
            if (options.size() > 1 && options.contains(output)) {
                // what a pattern makes never stands in for another item in its own slots
                options = options.stream().filter(k -> !k.equals(output)).toList();
            }
            groups.merge(options, pattern.amount(i), Long::sum);
        }
        if (growth != null) {
            uses.computeIfAbsent(pattern, k -> new LinkedHashMap<>()).merge(output, saturatingMul(crafts, growth.seed()), Long::sum);
        }
        // What each craft hands back is used again by the next one, so a mold that returns is needed once, not every time.
        Map<K, Long> reused = new HashMap<>();
        // Extra copies of what comes back, so that more crafts can run at once: wanted, never required.
        Map<List<K>, Long> extras = new LinkedHashMap<>();
        Map<List<K>, K> extraKeys = new HashMap<>();
        for (Map.Entry<List<K>, Long> group : groups.entrySet()) {
            long needed = saturatingMul(crafts, group.getValue());
            long unit = pattern.mixes() ? 1 : group.getValue();
            K key = crafts > 1 ? group.getKey().stream().filter(pattern.remainders()::containsKey).findFirst().orElse(null) : null;
            if (key != null) {
                long back = pattern.remainders().get(key);
                if (back > 0) {
                    long firstCraft = group.getValue();
                    long later = saturatingMul(crafts - 1, Math.max(0, group.getValue() - back));
                    long once = Math.min(needed, saturatingAdd(firstCraft, later));
                    reused.merge(key, needed - once, Long::sum);
                    needed = once;
                    unit = 1;
                    long extra = saturatingMul(Math.min(crafts, lanes) - 1, Math.min(back, group.getValue()));
                    if (extra > 0) {
                        extras.put(group.getKey(), extra);
                        extraKeys.put(group.getKey(), key);
                    }
                }
            }
            supply(group.getKey(), needed, unit, depth, pattern);
            if (tooComplex) {
                return;
            }
        }
        // Only once every slot has what it needs, so an extra copy never takes what another slot required.
        for (Map.Entry<List<K>, Long> extra : extras.entrySet()) {
            long lent = useAvailable(extra.getKey(), extra.getValue(), 1, pattern);
            // They come back after the step like the first copy.
            reused.merge(extraKeys.get(extra.getKey()), -lent, Long::sum);
        }
        addStep(pattern, crafts);
        if (tooComplex) {
            return;
        }
        // A growing step only ever holds its seed and what it gained: the copies it takes are made again.
        size = saturatingAdd(size, book.space(output, saturatingMul(crafts, growth == null ? pattern.outputCount() : growth.gain())));
        madeBy.put(output, pattern);
        for (Map.Entry<K, Long> left : pattern.remainders().entrySet()) {
            if (growth != null && left.getKey().equals(output)) {
                // counted in what it gains
                continue;
            }
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

    /**
     * {@code needed} items for slots that take {@code options}, from any of them, for the craft {@code consumer}; each
     * item in lots of {@code unit} (one craft's share, when a craft can't mix them).
     */
    private void supply(List<K> options, long needed, long unit, int depth, Pattern<K> consumer) {
        if (options.isEmpty()) {
            return;
        }
        needed -= useAvailable(options, needed, unit, consumer);
        if (needed <= 0) {
            return;
        }
        for (K option : options) {
            Pattern<K> pattern = main(option);
            if (pattern != null) {
                craft(option, pattern, needed, depth, consumer);
                uses.computeIfAbsent(consumer, k -> new LinkedHashMap<>()).merge(option, needed, Long::sum);
                return;
            }
        }
        for (K option : options) {
            if (!book.patternsFor(option).isEmpty()) {
                // patterns there are, but none can be used: say why
                Problem why = unusable(option);
                noGain |= why == Problem.NO_GAIN;
                tooComplex |= why == Problem.TOO_COMPLEX;
            }
        }
        missing.merge(options.getFirst(), needed, Long::sum);
        take(new Taking<>(consumer, null, Kind.MISSING, options.getFirst()));
    }

    /**
     * Takes up to {@code amount} of what is already there for {@code options}: the encoded item first, then the others,
     * most plentiful first (ties keep the encoded order). With options to choose from, each is taken in whole lots of
     * {@code unit}. Returns how many.
     */
    private long useAvailable(List<K> options, long amount, long unit, Pattern<K> consumer) {
        List<K> byAmount = new ArrayList<>(options.subList(1, options.size()));
        byAmount.sort(Comparator.comparingLong((K k) -> available(k)).reversed());
        byAmount.addFirst(options.getFirst());
        long lot = options.size() > 1 ? Math.max(1, unit) : 1;
        long got = 0;
        for (K option : byAmount) {
            if (got >= amount) {
                break;
            }
            long want = Math.min(amount - got, available(option)) / lot * lot;
            if (want > 0) {
                got += use(option, want, consumer);
            }
        }
        return got;
    }

    private void craft(K key, Pattern<K> pattern, long needed, int depth, Pattern<K> consumer) {
        if (depth >= MAX_DEPTH || making.contains(key)) {
            tooComplex = true;
            return;
        }
        Growth growth = growth(pattern);
        long each = pattern.outputCount();
        if (growth != null) {
            hold(key, growth.seed(), depth, pattern);
            if (tooComplex) {
                return;
            }
            each = growth.gain();
        }
        long crafts = ceilDiv(needed, each);
        making.push(key);
        run(pattern, crafts, depth + 1);
        making.pop();
        take(new Taking<>(consumer, pattern, null, null));
        long extra = saturatingMul(crafts, each) - needed;
        if (extra > 0) {
            spare.merge(key, extra, Long::sum);
        }
    }

    private long available(K key) {
        return spare.getOrDefault(key, 0L) + usableStock(key);
    }

    /**
     * What other crafts may take of {@code key} from storage: all of it, but a seed of an item that grows, until the
     * job holds a seed of its own.
     */
    private long usableStock(K key) {
        long stored = stock.getOrDefault(key, 0L);
        if (stored <= 0 || held.containsKey(key)) {
            return Math.max(0, stored);
        }
        Pattern<K> pattern = main(key);
        Growth growth = pattern == null ? null : growth(pattern);
        return growth == null ? stored : Math.max(0, stored - growth.seed());
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
        long fromStock = Math.min(amount - fromSpare, usableStock(key));
        if (fromStock > 0) {
            stock.merge(key, -fromStock, Long::sum);
            taken.merge(key, fromStock, Long::sum);
            take(new Taking<>(consumer, null, Kind.STORAGE, key));
        }
        if (fromSpare + fromStock > 0) {
            uses.computeIfAbsent(consumer, k -> new LinkedHashMap<>()).merge(key, fromSpare + fromStock, Long::sum);
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
            Growth growth = growth(step.pattern());
            long each = growth == null ? step.pattern().outputCount() : growth.gain();
            boxes.add(new Box<>(Kind.CRAFT, step.pattern().output(), saturatingMul(step.crafts(), each), step.crafts()));
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
