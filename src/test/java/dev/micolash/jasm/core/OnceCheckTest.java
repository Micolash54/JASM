package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OnceCheckTest {
    @Test
    void passingCheckRunsOncePerKey() {
        AtomicInteger calls = new AtomicInteger();
        OnceCheck<Object> check = new OnceCheck<>(key -> calls.incrementAndGet());
        Object server = new Object();
        check.ensure(server);
        check.ensure(server);
        assertEquals(1, calls.get());
        check.ensure(new Object());
        assertEquals(2, calls.get());
    }

    @Test
    void failureIsRememberedAndRethrownWithoutRerunning() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException corrupt = new IllegalStateException("corrupt state");
        OnceCheck<Object> check = new OnceCheck<>(key -> {
            calls.incrementAndGet();
            throw corrupt;
        });
        Object server = new Object();
        assertSame(corrupt, assertThrows(IllegalStateException.class, () -> check.ensure(server)));
        assertSame(corrupt, assertThrows(IllegalStateException.class, () -> check.ensure(server)));
        assertEquals(1, calls.get());
    }

    @Test
    void failureOnOneKeyDoesNotAffectAnother() {
        Object bad = new Object();
        OnceCheck<Object> check = new OnceCheck<>(key -> {
            if (key == bad) {
                throw new IllegalStateException("bad");
            }
        });
        assertThrows(IllegalStateException.class, () -> check.ensure(bad));
        assertDoesNotThrow(() -> check.ensure(new Object()));
    }
}
