package dev.heiko.universe.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GalaxyProfileTest {
    private static SectorKey key(Realm realm, long x, long y, long z) {
        return new SectorKey(realm, x, y, z);
    }

    @Test void milkyWayUsesFiniteDiskAndHalfOpenVerticalEnvelope() {
        var profile = GalaxyProfile.forRealm(Realm.MILKY_WAY);
        assertEquals(4096, profile.radiusX());
        assertEquals(256, profile.thickness());
        assertTrue(profile.contains(key(Realm.MILKY_WAY, -128, -4, 0)));
        assertTrue(profile.contains(key(Realm.MILKY_WAY, 127, 3, -1)));
        assertFalse(profile.contains(key(Realm.MILKY_WAY, -129, 0, 0)));
        assertFalse(profile.contains(key(Realm.MILKY_WAY, 128, 0, 0)));
        assertFalse(profile.contains(key(Realm.MILKY_WAY, 0, -5, 0)));
        assertFalse(profile.contains(key(Realm.MILKY_WAY, 0, 4, 0)));
        // A finite disk, not its bounding square.
        assertFalse(profile.contains(key(Realm.MILKY_WAY, 127, 0, 127)));
    }

    @Test void centerSelectionIsSymmetricAroundOriginIncludingNegativeSectors() {
        for (Realm realm : Realm.values()) {
            var profile = GalaxyProfile.forRealm(realm);
            for (long x : new long[]{-129, -128, -97, -96, -65, -64, -33, -32, -1, 0, 31, 63, 95, 127}) {
                for (long z : new long[]{-96, -32, -1, 0, 31, 95}) {
                    assertEquals(profile.contains(key(realm, x, -1, z)),
                            profile.contains(key(realm, -x - 1, 0, -z - 1)));
                }
            }
        }
        var negative = SectorKey.fromUnits(Realm.MILKY_WAY, -4096, -128, 0);
        assertEquals(key(Realm.MILKY_WAY, -128, -4, 0), negative);
        assertTrue(GalaxyProfile.MILKY_WAY_DISK.contains(negative));
        assertFalse(GalaxyProfile.MILKY_WAY_DISK.contains(
                SectorKey.fromUnits(Realm.MILKY_WAY, -4097, -128, 0)));
    }

    @Test void endAndNetherHaveSeparateStableProvisionalProfiles() {
        assertEquals("milky_way.disk.v1", GalaxyProfile.forRealm(Realm.MILKY_WAY).id());
        assertEquals("silent_crown.ring.v1", GalaxyProfile.forRealm(Realm.END).id());
        assertEquals("crimson_forge.ellipse.v1", GalaxyProfile.forRealm(Realm.NETHER).id());
        assertEquals("milky_way", Realm.MILKY_WAY.galaxyId());
        assertEquals("silent_crown", Realm.END.galaxyId());
        assertEquals("crimson_forge", Realm.NETHER.galaxyId());
        assertFalse(GalaxyProfile.END_RING.contains(key(Realm.END, 0, 0, 0)));
        assertTrue(GalaxyProfile.END_RING.contains(key(Realm.END, 32, 0, 0)));
        assertTrue(GalaxyProfile.END_RING.contains(key(Realm.END, -96, -8, 0)));
        assertFalse(GalaxyProfile.END_RING.contains(key(Realm.END, 96, 0, 0)));
        assertTrue(GalaxyProfile.NETHER_ELLIPSE.contains(key(Realm.NETHER, -64, -6, 0)));
        assertFalse(GalaxyProfile.NETHER_ELLIPSE.contains(key(Realm.NETHER, 64, 0, 0)));
        assertFalse(GalaxyProfile.NETHER_ELLIPSE.contains(key(Realm.END, 0, 0, 0)));
    }

    @Test void outsideCatalogIsEmptyAndLongExtremesCannotWrapIntoGalaxy() {
        var catalog = new LazyUniverseCatalog(987, Versions.CURRENT, 2);
        for (Realm realm : Realm.values()) {
            for (var sector : new SectorKey[]{key(realm, Long.MIN_VALUE, 0, 0),
                    key(realm, Long.MAX_VALUE, 0, 0), key(realm, 0, Long.MIN_VALUE, 0),
                    key(realm, 0, 0, Long.MAX_VALUE), key(realm, 128, 0, 0)}) {
                assertFalse(GalaxyProfile.forRealm(realm).contains(sector));
                assertTrue(catalog.sector(sector).systems().isEmpty());
                var system = new SystemKey(sector, 0);
                assertTrue(catalog.system(system).isEmpty());
                assertTrue(catalog.body(new BodyKey(system, BodyKey.Kind.STAR, -1, 0)).isEmpty());
                assertTrue(catalog.cachedSectorCount() <= 2);
            }
        }
    }

    @Test void insideAndOutsideSnapshotsRemainStableAcrossCacheEviction() {
        var catalog = new LazyUniverseCatalog(42, Versions.CURRENT, 1);
        for (Realm realm : Realm.values()) {
            var inside = key(realm, 40, 0, 0);
            var outside = key(realm, -129, 0, 0);
            var snapshot = catalog.sector(inside);
            var empty = catalog.sector(outside);
            assertTrue(empty.systems().isEmpty());
            assertEquals(snapshot, catalog.sector(inside));
            catalog.clearCache();
            assertEquals(empty, catalog.sector(outside));
            assertEquals(snapshot, new LazyUniverseCatalog(42, Versions.CURRENT, 1).sector(inside));
        }
    }
}
