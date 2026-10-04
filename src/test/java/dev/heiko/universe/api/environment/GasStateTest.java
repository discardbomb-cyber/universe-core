package dev.heiko.universe.api.environment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GasStateTest {
    @Test void vacuumHasNoComponentsAndNoPartialPressure() {
        var vacuum = GasState.vacuum();
        assertTrue(vacuum.isVacuum());
        assertEquals(0, vacuum.totalPressureGameUnits());
        assertTrue(vacuum.fractions().isEmpty());
        assertEquals(0, vacuum.partialPressure("test:oxygen"));
        assertEquals(vacuum, new GasState(0, Map.of()));
    }

    @Test void gasMixtureIsCopiedSortedAndImmutableWhilePartialPressuresRemainExplicit() {
        var source = new LinkedHashMap<String, Double>();
        source.put("test:oxygen", 0.25);
        source.put("test:nitrogen", 0.75);
        var state = new GasState(80, source);
        source.clear();
        assertEquals(java.util.List.of("test:nitrogen", "test:oxygen"), new ArrayList<>(state.fractions().keySet()));
        assertEquals(20, state.partialPressure("test:oxygen"));
        assertEquals(60, state.partialPressure("test:nitrogen"));
        assertEquals(0, state.partialPressure("test:unknown"));
        assertFalse(state.isVacuum());
        assertThrows(UnsupportedOperationException.class, () -> state.fractions().put("test:argon", 0.01));
    }

    @Test void acceptedSumToleranceDoesNotNormalizeOrRewriteSuppliedFractions() {
        var input = Map.of("test:a", 0.5, "test:b", 0.5000000005);
        var state = new GasState(20, input);
        assertEquals(input, state.fractions());
        assertEquals(20 * 0.5000000005, state.partialPressure("test:b"));
        assertThrows(IllegalArgumentException.class, () -> new GasState(20, Map.of("test:a", 0.5, "test:b", 0.500000002)));
        assertThrows(IllegalArgumentException.class, () -> new GasState(20, Map.of("test:a", 0.5, "test:b", 0.499999998)));
    }

    @Test void invalidPressureAndEmptyOrZeroPressureMixturesFailExplicitly() {
        for (double pressure : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new GasState(pressure, Map.of("test:oxygen", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new GasState(10, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GasState(0, Map.of("test:oxygen", 1.0)));
    }

    @Test void componentsArePositiveFiniteBoundedAndLimitedToSixteen() {
        for (double fraction : new double[]{0, -0.1, Math.nextUp(1.0), Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new GasState(1, Map.of("test:gas", fraction)));
        var components = new LinkedHashMap<String, Double>();
        for (int index = 0; index < 16; index++) components.put("test:gas_" + index, 1.0 / 16);
        assertEquals(16, new GasState(1, components).fractions().size());
        components.replaceAll((id, fraction) -> 1.0 / 17);
        components.put("test:gas_16", 1.0 / 17);
        assertThrows(IllegalArgumentException.class, () -> new GasState(1, components));
    }

    @Test void gasAndSpeciesIdentifiersAndProfileRevisionAreValidated() {
        for (String invalid : new String[]{"oxygen", "Test:oxygen", "test:bad path", "test:/gas", "test:a//b", "test:../gas"}) {
            assertThrows(IllegalArgumentException.class, () -> new GasState(1, Map.of(invalid, 1.0)));
            assertThrows(IllegalArgumentException.class, () -> new GasProfile(invalid, 0, GasState.vacuum()));
            assertThrows(IllegalArgumentException.class, () -> GasState.vacuum().partialPressure(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new GasProfile("test:gas", -1, GasState.vacuum()));
        assertEquals(0, new GasProfile("test:gas", 0, GasState.vacuum()).revision());
    }
}
