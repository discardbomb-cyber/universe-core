package dev.heiko.universe.core;

import java.util.Objects;

public record SectorKey(Realm realm, long x, long y, long z) {
    public static final int SIDE = 32;
    public SectorKey { Objects.requireNonNull(realm, "realm"); }
    public static SectorKey fromUnits(Realm realm, long x, long y, long z) {
        return new SectorKey(realm, Math.floorDiv(x, SIDE), Math.floorDiv(y, SIDE), Math.floorDiv(z, SIDE));
    }
}

