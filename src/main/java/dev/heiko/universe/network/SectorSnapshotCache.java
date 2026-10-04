package dev.heiko.universe.network;

import dev.heiko.universe.core.SectorKey;
import dev.heiko.universe.core.Versions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Plain Java snapshots. No client classes, screens, seeds or block data. */
public final class SectorSnapshotCache {
    public record Snapshot(UUID worldId, int requestId, SectorKey key, Versions versions,
                           int systemCount, int bodyCount) {}
    private static final LinkedHashMap<SectorKey, Snapshot> SNAPSHOTS = new LinkedHashMap<>();
    private static UUID worldId;
    private SectorSnapshotCache() {}

    static synchronized void accept(Snapshot snapshot) {
        if (!snapshot.worldId().equals(worldId)) {
            SNAPSHOTS.clear();
            worldId = snapshot.worldId();
        }
        Snapshot previous = SNAPSHOTS.get(snapshot.key());
        if (previous != null && previous.requestId() > snapshot.requestId()) return;
        SNAPSHOTS.remove(snapshot.key());
        SNAPSHOTS.put(snapshot.key(), snapshot);
        if (SNAPSHOTS.size() > SectorProtocol.CACHE_CAPACITY)
            SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
    }

    public static synchronized List<Snapshot> snapshots() { return List.copyOf(SNAPSHOTS.values()); }
    /** Call when leaving a server, before displaying another session's map. */
    public static synchronized void clear() { SNAPSHOTS.clear(); worldId = null; }
}
