package dev.heiko.universe.client.map;

import dev.heiko.universe.core.Realm;
import dev.heiko.universe.network.SectorSnapshotCache;

/** Exact outstanding request match; cached older realms must never repaint a new selection. */
public final class MapSectorSelection {
    private MapSectorSelection() {}
    public static boolean matches(SectorSnapshotCache.Snapshot snapshot, int requestId, Realm realm) {
        var key = snapshot.key();
        return requestId >= 0 && snapshot.requestId() == requestId && key.realm() == realm
                && key.x() == 0 && key.y() == 0 && key.z() == 0;
    }
}
