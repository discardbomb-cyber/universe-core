package dev.heiko.universe.core;

import java.util.Objects;

/** For moons parentSlot is the planet slot; for all other kinds it is -1. */
public record BodyKey(SystemKey system, Kind kind, int parentSlot, int slot) {
    public enum Kind { STAR, PLANET, MOON, BELT }
    public BodyKey {
        Objects.requireNonNull(system, "system"); Objects.requireNonNull(kind, "kind");
        int limit = switch (kind) { case STAR -> 1; case PLANET -> 12; case MOON -> 4; case BELT -> 2; };
        if (slot < 0 || slot >= limit) throw new IllegalArgumentException("Invalid body slot");
        if (kind == Kind.MOON ? parentSlot < 0 || parentSlot >= 12 : parentSlot != -1)
            throw new IllegalArgumentException("Invalid parent slot");
    }
}

