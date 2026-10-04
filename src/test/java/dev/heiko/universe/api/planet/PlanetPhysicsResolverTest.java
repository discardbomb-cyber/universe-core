package dev.heiko.universe.api.planet;

import dev.heiko.universe.api.environment.GasProfile;
import dev.heiko.universe.api.environment.GasState;
import dev.heiko.universe.api.gravity.VerticalGravityProfile;
import dev.heiko.universe.core.Realm;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanetPhysicsResolverTest {
    private static final String PLANET = "test:home", GRAVITY = "test:gravity", GAS = "test:gas", FRAME = "test:surface";

    private static PlanetDefinition planet(String id, long seed) {
        return new PlanetDefinition(id, Realm.MILKY_WAY, seed, "test:terrain", "test:atmosphere", "test:surface");
    }

    private static VerticalGravityProfile gravity(String id, double acceleration) {
        return new VerticalGravityProfile(id, 1, acceleration, 100, 200);
    }

    private static GasProfile gas(String id, double pressure) {
        return new GasProfile(id, 2, new GasState(pressure, Map.of("test:oxygen", 0.25, "test:nitrogen", 0.75)));
    }

    private static PlanetPhysicsBinding binding() {
        return new PlanetPhysicsBinding(PLANET, GRAVITY, GAS, FRAME);
    }

    @Test void resolvesPlanetGravityGasAndCallerFrameWithoutMutatingRegistries() {
        var planets = Map.of(PLANET, planet(PLANET, 42));
        var gravities = Map.of(GRAVITY, gravity(GRAVITY, 9.81));
        var gases = Map.of(GAS, gas(GAS, 80));
        var resolved = PlanetPhysicsResolver.resolve(binding(), planets, gravities, gases);
        assertSame(planets.get(PLANET), resolved.planet());
        assertSame(gravities.get(GRAVITY), resolved.gravity());
        assertSame(gases.get(GAS), resolved.gas());
        assertEquals(FRAME, resolved.frameId());
        assertEquals(FRAME, resolved.gravityAt(150).frameId());
        assertEquals(-4.905, resolved.gravityAt(150).accelerationMetersPerSecondSquared().y(), 1e-12);
        assertEquals(20, resolved.partialPressure("test:oxygen"));
        assertEquals(1, planets.size());
        assertEquals(1, gravities.size());
        assertEquals(1, gases.size());
    }

    @Test void everyMissingReferenceReportsItsKindAndId() {
        var planets = Map.of(PLANET, planet(PLANET, 0));
        var gravities = Map.of(GRAVITY, gravity(GRAVITY, 9.81));
        var gases = Map.of(GAS, gas(GAS, 80));
        assertFailure("planet", PLANET, () -> PlanetPhysicsResolver.resolve(binding(), Map.of(), gravities, gases));
        assertFailure("gravity", GRAVITY, () -> PlanetPhysicsResolver.resolve(binding(), planets, Map.of(), gases));
        assertFailure("gas", GAS, () -> PlanetPhysicsResolver.resolve(binding(), planets, gravities, Map.of()));
    }

    @Test void mapKeysCannotDisguiseMismatchedDefinitionIds() {
        var planets = Map.of(PLANET, planet(PLANET, 0));
        var gravities = Map.of(GRAVITY, gravity(GRAVITY, 9.81));
        var gases = Map.of(GAS, gas(GAS, 80));
        assertFailure("planet", PLANET, () -> PlanetPhysicsResolver.resolve(binding(), Map.of(PLANET, planet("test:other", 0)), gravities, gases));
        assertFailure("gravity", GRAVITY, () -> PlanetPhysicsResolver.resolve(binding(), planets, Map.of(GRAVITY, gravity("test:other", 1)), gases));
        assertFailure("gas", GAS, () -> PlanetPhysicsResolver.resolve(binding(), planets, gravities, Map.of(GAS, gas("test:other", 1))));
    }

    @Test void contextsWithIdenticalIdsResolveIndependently() {
        var first = PlanetPhysicsResolver.resolve(binding(), Map.of(PLANET, planet(PLANET, 1)),
                Map.of(GRAVITY, gravity(GRAVITY, 9.81)), Map.of(GAS, gas(GAS, 80)));
        var second = PlanetPhysicsResolver.resolve(binding(), Map.of(PLANET, planet(PLANET, 2)),
                Map.of(GRAVITY, gravity(GRAVITY, 1.62)), Map.of(GAS, new GasProfile(GAS, 3, GasState.vacuum())));
        assertEquals(1, first.planet().seed());
        assertEquals(2, second.planet().seed());
        assertEquals(-9.81, first.gravityAt(0).accelerationMetersPerSecondSquared().y());
        assertEquals(-1.62, second.gravityAt(0).accelerationMetersPerSecondSquared().y());
        assertEquals(20, first.partialPressure("test:oxygen"));
        assertEquals(0, second.partialPressure("test:oxygen"));
    }

    @Test void resolvedSnapshotRetainsProfilesAfterHostReplacesEntries() {
        var planets = new HashMap<>(Map.of(PLANET, planet(PLANET, 1)));
        var gravities = new HashMap<>(Map.of(GRAVITY, gravity(GRAVITY, 9.81)));
        var gases = new HashMap<>(Map.of(GAS, gas(GAS, 80)));
        var original = PlanetPhysicsResolver.resolve(binding(), planets, gravities, gases);
        planets.put(PLANET, planet(PLANET, 2));
        gravities.put(GRAVITY, gravity(GRAVITY, 1.62));
        gases.put(GAS, new GasProfile(GAS, 3, GasState.vacuum()));
        var updated = PlanetPhysicsResolver.resolve(binding(), planets, gravities, gases);
        assertEquals(1, original.planet().seed());
        assertEquals(20, original.partialPressure("test:oxygen"));
        assertEquals(-9.81, original.gravityAt(0).accelerationMetersPerSecondSquared().y());
        assertEquals(2, updated.planet().seed());
        assertTrue(updated.gas().state().isVacuum());
    }

    @Test void bindingsRejectInvalidIdsInEveryReferencePosition() {
        for (String invalid : new String[]{"plain", "TEST:id", "test:bad path", "test:../id", "test:a//b"}) {
            assertThrows(IllegalArgumentException.class, () -> new PlanetPhysicsBinding(invalid, GRAVITY, GAS, FRAME));
            assertThrows(IllegalArgumentException.class, () -> new PlanetPhysicsBinding(PLANET, invalid, GAS, FRAME));
            assertThrows(IllegalArgumentException.class, () -> new PlanetPhysicsBinding(PLANET, GRAVITY, invalid, FRAME));
            assertThrows(IllegalArgumentException.class, () -> new PlanetPhysicsBinding(PLANET, GRAVITY, GAS, invalid));
        }
    }

    private static void assertFailure(String kind, String id, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(IllegalArgumentException.class, operation);
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains(kind), failure.getMessage());
        assertTrue(failure.getMessage().contains(id), failure.getMessage());
    }
}
