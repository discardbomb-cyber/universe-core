package dev.heiko.universe.core;

import java.util.Objects;

/**
 * Finite catalog envelopes in conditional galaxy units, not physical dimensions.
 * A sector belongs when its center is inside the envelope. Y is half-open
 * [-thickness/2, thickness/2); radial boundaries are inclusive.
 * End/Nether envelopes are provisional, not final density or placement profiles.
 */
public enum GalaxyProfile {
    MILKY_WAY_DISK("milky_way.disk.v1", Realm.MILKY_WAY, 4096, 4096, 0, 256),
    END_RING("silent_crown.ring.v1", Realm.END, 3072, 3072, 1024, 512),
    NETHER_ELLIPSE("crimson_forge.ellipse.v1", Realm.NETHER, 2048, 3072, 0, 384);

    private final String id;
    private final Realm realm;
    private final int radiusX;
    private final int radiusZ;
    private final int innerRadius;
    private final int thickness;

    GalaxyProfile(String id, Realm realm, int radiusX, int radiusZ, int innerRadius, int thickness) {
        this.id = id; this.realm = realm; this.radiusX = radiusX; this.radiusZ = radiusZ;
        this.innerRadius = innerRadius; this.thickness = thickness;
    }
    public String id() { return id; }
    public Realm realm() { return realm; }
    public int radiusX() { return radiusX; }
    public int radiusZ() { return radiusZ; }
    public int innerRadius() { return innerRadius; }
    public int thickness() { return thickness; }

    public static GalaxyProfile forRealm(Realm realm) {
        return switch (Objects.requireNonNull(realm)) {
            case MILKY_WAY -> MILKY_WAY_DISK;
            case END -> END_RING;
            case NETHER -> NETHER_ELLIPSE;
        };
    }

    public boolean contains(SectorKey key) {
        Objects.requireNonNull(key);
        if (key.realm() != realm) return false;
        // Reject before multiplication: arbitrary long addresses cannot overflow.
        if (key.x() < -radiusX / SectorKey.SIDE || key.x() >= radiusX / SectorKey.SIDE
                || key.z() < -radiusZ / SectorKey.SIDE || key.z() >= radiusZ / SectorKey.SIDE
                || key.y() < -thickness / (2 * SectorKey.SIDE)
                || key.y() >= thickness / (2 * SectorKey.SIDE)) return false;
        long x = key.x() * SectorKey.SIDE + SectorKey.SIDE / 2;
        long z = key.z() * SectorKey.SIDE + SectorKey.SIDE / 2;
        // All operands now bounded; integer ellipse comparison is exact.
        long rx2 = (long) radiusX * radiusX;
        long rz2 = (long) radiusZ * radiusZ;
        return x * x * rz2 + z * z * rx2 <= rx2 * rz2
                && x * x + z * z >= (long) innerRadius * innerRadius;
    }
}
