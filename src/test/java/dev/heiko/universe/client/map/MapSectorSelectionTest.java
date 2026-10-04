package dev.heiko.universe.client.map;

import dev.heiko.universe.core.Realm;
import dev.heiko.universe.core.SectorKey;
import dev.heiko.universe.core.Versions;
import dev.heiko.universe.network.SectorSnapshotCache;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSectorSelectionTest {
    private static SectorSnapshotCache.Snapshot reply(int id, Realm realm, long x) {
        return new SectorSnapshotCache.Snapshot(new UUID(0, 1), id, new SectorKey(realm, x, 0, 0), Versions.CURRENT, 1, 2);
    }

    @Test void rejectsStaleFutureWrongRealmAndWrongSectorReplies() {
        assertTrue(MapSectorSelection.matches(reply(4, Realm.END, 0), 4, Realm.END));
        assertFalse(MapSectorSelection.matches(reply(3, Realm.END, 0), 4, Realm.END));
        assertFalse(MapSectorSelection.matches(reply(5, Realm.END, 0), 4, Realm.END));
        assertFalse(MapSectorSelection.matches(reply(4, Realm.MILKY_WAY, 0), 4, Realm.END));
        assertFalse(MapSectorSelection.matches(reply(4, Realm.END, 1), 4, Realm.END));
        assertFalse(MapSectorSelection.matches(reply(4, Realm.END, 0), -1, Realm.END));
    }
}
