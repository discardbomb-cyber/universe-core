package dev.heiko.universe.core;

import java.util.Objects;

public record SystemKey(SectorKey sector, int slot) {
    public SystemKey {
        Objects.requireNonNull(sector, "sector");
        if (slot < 0 || slot >= 8) throw new IllegalArgumentException("System slot must be 0..7");
    }
}

