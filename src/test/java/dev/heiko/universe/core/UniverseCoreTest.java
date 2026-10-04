package dev.heiko.universe.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UniverseCoreTest {
    private static SectorKey sector(Realm realm, long x) { return new SectorKey(realm, x, 2, 3); }
    private static BodyKey planet() {
        return new BodyKey(new SystemKey(sector(Realm.MILKY_WAY, 0), 0), BodyKey.Kind.PLANET, -1, 0);
    }

    @Test void negativeCoordinateBoundariesUseFloorDivision() {
        int[] chunks = {Integer.MIN_VALUE, -33, -32, -1, 0, 31, 32, Integer.MAX_VALUE};
        for (int chunk : chunks) {
            var region = RegionKey.fromChunk(planet(), 0, chunk, chunk);
            int local = RegionKey.localChunk(chunk);
            assertTrue(local >= 0 && local < 32);
            assertEquals(chunk, (long) region.x() * 32 + local);
            assertEquals(region.x(), region.z());
        }
        assertEquals(-1, RegionKey.fromChunk(planet(), 0, -1, 0).x());
        assertEquals(31, RegionKey.localChunk(-1));
        assertEquals(-1, SectorKey.fromUnits(Realm.END, -1, -32, -33).x());
        assertEquals(-2, SectorKey.fromUnits(Realm.END, -1, -32, -33).z());
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
            var key = SectorKey.fromUnits(Realm.END, value, value, value);
            assertEquals(value, key.x() * 32 + Math.floorMod(value, 32));
        }
    }

    @Test void canonicalHashHasPinnedIndependentGoldenVector() {
        assertEquals(-9200311242907148408L, StableSeed.derive(42, Versions.CURRENT,
                sector(Realm.MILKY_WAY, -1), "terrain", 7, 9));
    }

    @Test void realmAndFieldDomainsAreIndependent() {
        var seeds = new HashSet<Long>();
        var addresses = new HashSet<SectorKey>();
        for (Realm realm : Realm.values()) {
            var key = sector(realm, -1);
            addresses.add(key);
            seeds.add(StableSeed.derive(42, Versions.CURRENT, key, "terrain", 7, 9));
        }
        assertEquals(3, addresses.size());
        assertEquals(3, seeds.size());
        var key = sector(Realm.END, 0);
        assertNotEquals(StableSeed.derive(42, Versions.CURRENT, key, "a", 1, 23),
                StableSeed.derive(42, Versions.CURRENT, key, "a", 12, 3));
        assertNotEquals(StableSeed.derive(42, Versions.CURRENT, key, "orbit"),
                StableSeed.derive(42, Versions.CURRENT, key, "terrain"));
    }

    @Test void queryOrderEvictionAndCacheLossDoNotChangeCatalog() {
        var keys = new ArrayList<SectorKey>();
        for (Realm realm : Realm.values()) for (int x = -10; x <= 10; x++) keys.add(sector(realm, x));
        var catalog = new LazyUniverseCatalog(789, Versions.CURRENT, 2);
        assertEquals(0, catalog.cachedSectorCount());
        var expected = keys.stream().map(catalog::sector).toList();
        Collections.reverse(keys);
        for (SectorKey key : keys) {
            assertEquals(expected.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow(), catalog.sector(key));
            assertTrue(catalog.cachedSectorCount() <= 2);
        }
        catalog.clearCache();
        assertEquals(0, catalog.cachedSectorCount());
        for (SectorDescriptor descriptor : expected) assertEquals(descriptor, catalog.sector(descriptor.key()));
        var other = new LazyUniverseCatalog(789, Versions.CURRENT, 1);
        for (SectorDescriptor descriptor : expected) assertEquals(descriptor, other.sector(descriptor.key()));
    }

    @Test void expansionIsFiniteAndDescriptorsAreImmutable() {
        var catalog = new LazyUniverseCatalog(42, Versions.CURRENT, 1);
        for (int x = -50; x < 50; x++) {
            var descriptor = catalog.sector(sector(Realm.NETHER, x));
            assertTrue(descriptor.systems().size() <= 8);
            assertThrows(UnsupportedOperationException.class, () -> descriptor.systems().clear());
            for (var system : descriptor.systems()) {
                assertEquals(system, catalog.system(system.key()).orElseThrow());
                assertEquals(1, system.bodies().stream().filter(b -> b.key().kind() == BodyKey.Kind.STAR).count());
                assertTrue(system.bodies().size() <= 63);
                assertThrows(UnsupportedOperationException.class, () -> system.bodies().clear());
                for (var body : system.bodies()) assertEquals(body, catalog.body(body.key()).orElseThrow());
            }
            for (int slot = descriptor.systems().size(); slot < 8; slot++)
                assertTrue(catalog.system(new SystemKey(descriptor.key(), slot)).isEmpty());
        }
    }

    @Test void rejectsInvalidAddressesVersionsAndBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new Versions(2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Versions(1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new LazyUniverseCatalog(1, Versions.CURRENT, 0));
        assertThrows(IllegalArgumentException.class, () -> new LazyUniverseCatalog(1, Versions.CURRENT, 4097));
        var system = planet().system();
        assertThrows(IllegalArgumentException.class, () -> new SystemKey(system.sector(), 8));
        assertThrows(IllegalArgumentException.class, () -> new BodyKey(system, BodyKey.Kind.MOON, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BodyKey(system, BodyKey.Kind.PLANET, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RegionKey(planet(), 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SectorDescriptor(system.sector(), Versions.CURRENT,
                List.of(new SystemDescriptor(system, 0, Versions.CURRENT, List.of()),
                        new SystemDescriptor(system, 0, Versions.CURRENT, List.of()))));
    }
}

