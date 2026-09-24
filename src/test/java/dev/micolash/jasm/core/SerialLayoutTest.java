package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SerialLayoutTest {
    @Test
    void firstRegionFillsRowByRow() {
        assertEquals(new SerialLayout.Slot(0, 0), SerialLayout.slot(1));
        assertEquals(new SerialLayout.Slot(31, 0), SerialLayout.slot(32));
        assertEquals(new SerialLayout.Slot(0, 1), SerialLayout.slot(33));
        assertEquals(new SerialLayout.Slot(31, 31), SerialLayout.slot(1024));
    }

    @Test
    void nextRegionStartsAlongX() {
        assertEquals(new SerialLayout.Slot(32, 0), SerialLayout.slot(1025));
        assertEquals(new SerialLayout.Slot(64, 0), SerialLayout.slot(2049));
    }

    @Test
    void everySerialGetsItsOwnSlotInsideItsRegion() {
        Set<SerialLayout.Slot> seen = new HashSet<>();
        for (long serial = 1; serial <= 5_000; serial++) {
            SerialLayout.Slot slot = SerialLayout.slot(serial);
            assertEquals(true, seen.add(slot), "slot reused for serial " + serial);
            assertEquals((serial - 1) / SerialLayout.SLOTS_PER_REGION, Math.floorDiv(slot.x(), 32), "region of serial " + serial);
            assertEquals(0, Math.floorDiv(slot.z(), 32), "all regions sit on z = 0");
        }
    }

    @Test
    void serialZeroAndNegativeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SerialLayout.slot(0));
        assertThrows(IllegalArgumentException.class, () -> SerialLayout.slot(-5));
    }
}
