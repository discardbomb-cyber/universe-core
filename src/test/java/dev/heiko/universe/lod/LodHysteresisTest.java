package dev.heiko.universe.lod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodHysteresisTest {
    @Test void boundariesAndContinuousOutsideDelay() {
        var h = new LodHysteresis(1024, 1280, 40);
        assertFalse(h.update(1025, 0));
        assertTrue(h.update(1024, 1));
        for (int i = 2; i < 102; i++) assertTrue(h.update(i % 2 == 0 ? 1025 : 1279, i));
        assertTrue(h.update(1281, 102));
        assertTrue(h.update(1281, 141));
        assertTrue(h.update(1280, 142)); // Exact leave boundary resets delay.
        assertTrue(h.update(1281, 143));
        assertFalse(h.update(1281, 183));
    }
    @Test void invalidInputAndImmediateDowngrade() {
        assertThrows(IllegalArgumentException.class, () -> new LodHysteresis(2, 2, 0));
        var h = new LodHysteresis(1, 2, 0);
        assertTrue(h.update(1, 2)); assertFalse(h.update(3, 3));
        assertThrows(IllegalArgumentException.class, () -> h.update(1, 2));
        assertThrows(IllegalArgumentException.class, () -> h.update(Double.NaN, 4));
    }
}
