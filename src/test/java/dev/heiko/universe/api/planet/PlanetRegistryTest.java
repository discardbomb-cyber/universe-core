package dev.heiko.universe.api.planet;

import dev.heiko.universe.core.Realm;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanetRegistryTest {
    private static PlanetDefinition planet(String id, Realm realm, long seed) {
        return new PlanetDefinition(id, realm, seed, "addon:rocky/v1", "addon:none/v1", "addon:surface/home");
    }

    @Test void duplicateIdAcrossRealmsIsRejectedWithoutMutation() {
        var registry = new PlanetRegistry(2);
        var first = planet("addon:home", Realm.MILKY_WAY, 42);
        registry.register(first);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(planet("addon:home", Realm.END, 43)));
        assertEquals(1, registry.size());
        assertEquals(1, registry.revision());
        assertEquals(first, registry.find("addon:home").orElseThrow());
        assertTrue(registry.find("addon:unknown").isEmpty());
    }

    @Test void allReferencesRequireExplicitValidBoundedIds() {
        for (String invalid : new String[]{"home", ":home", "addon:", "ADDON:home", "addon:Home",
                "addon:a:b", "addon:has space", "addon:/home", "addon:home/", "addon:a//b",
                "addon:../home", "addon:a/./b", "a:" + "x".repeat(255)}) {
            assertThrows(IllegalArgumentException.class, () -> planet(invalid, Realm.END, 0), invalid);
            assertThrows(IllegalArgumentException.class, () ->
                    new PlanetDefinition("addon:home", Realm.END, 0, invalid, "addon:none", "addon:surface"));
            assertThrows(IllegalArgumentException.class, () ->
                    new PlanetDefinition("addon:home", Realm.END, 0, "addon:terrain", invalid, "addon:surface"));
            assertThrows(IllegalArgumentException.class, () ->
                    new PlanetDefinition("addon:home", Realm.END, 0, "addon:terrain", "addon:none", invalid));
        }
        assertThrows(NullPointerException.class, () -> planet(null, Realm.END, 0));
        assertThrows(NullPointerException.class, () -> planet("addon:home", null, 0));
        assertDoesNotThrow(() -> planet("a:" + "x".repeat(254), Realm.NETHER, Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, planet("my-addon:worlds/ice_2", Realm.NETHER, Long.MAX_VALUE).seed());
    }

    @Test void freezeIsIdempotentAndSnapshotsAreIsolated() {
        var registry = new PlanetRegistry();
        registry.register(planet("addon:first", Realm.MILKY_WAY, 1));
        var before = registry.snapshot();
        registry.register(planet("addon:second", Realm.END, 2));
        assertEquals(1, before.definitions().size());
        assertFalse(before.frozen());
        var frozen = registry.freeze();
        assertTrue(registry.isFrozen());
        assertTrue(frozen.frozen());
        assertEquals(PlanetRegistry.FORMAT_VERSION, frozen.formatVersion());
        assertEquals(2, frozen.revision());
        assertEquals(frozen, registry.freeze());
        assertThrows(IllegalStateException.class,
                () -> registry.register(planet("addon:third", Realm.NETHER, 3)));
        assertEquals(frozen, registry.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> frozen.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
    }

    @Test void fiveThousandMetadataDefinitionsNeedNoWorldOrClient() {
        var registry = new PlanetRegistry();
        var expected = new ArrayList<PlanetDefinition>();
        for (int index = 0; index < 5000; index++) {
            var definition = planet("test:planet_" + index, Realm.values()[index % 3], index * 1000003L);
            expected.add(definition);
            registry.register(definition);
        }
        assertEquals(5000, registry.size());
        assertEquals(5000, registry.revision());
        assertEquals(expected, registry.definitions());
        for (PlanetDefinition definition : expected)
            assertEquals(definition, registry.find(definition.id()).orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> registry.register(planet("test:overflow", Realm.MILKY_WAY, 0)));
        assertEquals(expected, registry.freeze().definitions());
    }

    @Test void capacityAndUnsupportedFormatsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetRegistry(0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetRegistry(PlanetRegistry.MAX_CAPACITY + 1));
        var registry = new PlanetRegistry(1);
        registry.register(planet("addon:first", Realm.END, 1));
        assertThrows(IllegalStateException.class,
                () -> registry.register(planet("addon:second", Realm.END, 2)));
        assertEquals(1, registry.revision());
        assertThrows(IllegalArgumentException.class, () ->
                new PlanetRegistry.Snapshot(2, 0, false, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new PlanetRegistry.Snapshot(1, 1, false, List.of()));
        var definition = planet("addon:home", Realm.END, 0);
        assertThrows(IllegalArgumentException.class, () ->
                new PlanetRegistry.Snapshot(1, 2, false, List.of(definition, definition)));
    }

    @Test void recordsAndSnapshotOrderRemainStableWithoutResolvingProfiles() {
        var definition = planet("addon:home", Realm.MILKY_WAY, 42);
        assertEquals(definition, planet("addon:home", Realm.MILKY_WAY, 42));
        var source = new ArrayList<>(List.of(definition));
        var snapshot = new PlanetRegistry.Snapshot(1, 1, true, source);
        source.clear();
        assertEquals(List.of(definition), snapshot.definitions());
        var empty = new PlanetRegistry(1);
        assertTrue(empty.freeze().definitions().isEmpty());
        assertThrows(IllegalStateException.class, () -> empty.register(definition));
    }
}

