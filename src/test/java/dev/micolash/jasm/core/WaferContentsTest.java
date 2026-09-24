package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaferContentsTest {
    @Test
    void insertFillsExactlyToCapacityAndReportsRemainder() {
        WaferContents<String> contents = new WaferContents<>();
        assertEquals(200, contents.insert("stone", 200, 256, false));
        assertEquals(56, contents.insert("dirt", 64, 256, false));
        assertEquals(0, contents.insert("sand", 1, 256, false));
        assertEquals(256, contents.total());
        assertEquals(56, contents.count("dirt"));
    }

    @Test
    void simulateDoesNotMutate() {
        WaferContents<String> contents = new WaferContents<>();
        assertEquals(10, contents.insert("stone", 10, 256, true));
        assertTrue(contents.isEmpty());
        contents.insert("stone", 10, 256, false);
        assertEquals(4, contents.extract("stone", 4, true));
        assertEquals(10, contents.count("stone"));
    }

    @Test
    void extractToZeroRemovesKey() {
        WaferContents<String> contents = new WaferContents<>();
        contents.insert("stone", 10, 256, false);
        assertEquals(10, contents.extract("stone", 64, false));
        assertTrue(contents.isEmpty());
        assertEquals(0, contents.total());
    }

    @Test
    void capacityBelowTotalAcceptsNothing() {
        WaferContents<String> contents = new WaferContents<>();
        contents.putLoaded("stone", 300);
        assertEquals(0, contents.insert("stone", 1, 256, false));
    }

    @Test
    void totalsBeyondIntRange() {
        WaferContents<String> contents = new WaferContents<>();
        contents.putLoaded("a", Integer.MAX_VALUE);
        contents.putLoaded("b", Integer.MAX_VALUE);
        assertEquals(2L * Integer.MAX_VALUE, contents.total());
    }

    @Test
    void rejectsNegativeAmounts() {
        WaferContents<String> contents = new WaferContents<>();
        assertThrows(IllegalArgumentException.class, () -> contents.insert("a", -1, 10, false));
        assertThrows(IllegalArgumentException.class, () -> contents.extract("a", -1, false));
        assertThrows(IllegalArgumentException.class, () -> contents.putLoaded("a", 0));
    }
}
