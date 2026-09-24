package dev.micolash.jasm.core;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Runs a check once per key. A failure is remembered and rethrown on every later call without re-running the
 * check, so a caller that catches the first failure can never proceed past it.
 */
public final class OnceCheck<K> {
    private final Consumer<K> check;
    private final Map<K, RuntimeException> failures = new WeakHashMap<>();
    private final Map<K, Boolean> passed = new WeakHashMap<>();

    public OnceCheck(Consumer<K> check) {
        this.check = check;
    }

    public void ensure(K key) {
        RuntimeException failure = failures.get(key);
        if (failure != null) {
            throw failure;
        }
        if (passed.containsKey(key)) {
            return;
        }
        try {
            check.accept(key);
        } catch (RuntimeException e) {
            failures.put(key, e);
            throw e;
        }
        passed.put(key, Boolean.TRUE);
    }
}
