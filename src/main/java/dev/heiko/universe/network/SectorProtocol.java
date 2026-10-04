package dev.heiko.universe.network;

import dev.heiko.universe.core.Realm;
import dev.heiko.universe.core.SectorKey;

/** Shared bounds; coordinates are sector addresses, not block positions. */
public final class SectorProtocol {
    public static final long MAX_COORDINATE = 1_000_000;
    public static final int CACHE_CAPACITY = 256;
    private SectorProtocol() {}

    public static SectorKey key(String realmId, long x, long y, long z) {
        if (!coordinate(x) || !coordinate(y) || !coordinate(z)) return null;
        for (Realm realm : Realm.values()) {
            if (realm.id().equals(realmId)) return new SectorKey(realm, x, y, z);
        }
        return null;
    }

    private static boolean coordinate(long value) {
        return value >= -MAX_COORDINATE && value <= MAX_COORDINATE;
    }
}
