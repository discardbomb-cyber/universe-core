package dev.heiko.universe.ships;

import java.util.Objects;
import java.util.UUID;

/** Stable identity independent of pose, dimension and loaded entity. */
public record ShipId(UUID value) implements Comparable<ShipId> {
    public ShipId { Objects.requireNonNull(value, "value"); }
    public static ShipId parse(String value) { return new ShipId(UUID.fromString(value)); }
    @Override public int compareTo(ShipId other) { return value.compareTo(other.value); }
}
