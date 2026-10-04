package dev.heiko.universe.core;

import java.util.List;
import java.util.Objects;

public record SystemDescriptor(SystemKey key, long seed, Versions versions, List<BodyDescriptor> bodies) {
    public SystemDescriptor {
        Objects.requireNonNull(key); Objects.requireNonNull(versions); bodies = List.copyOf(bodies);
        if (bodies.size() > 63 || bodies.stream().anyMatch(b -> !b.key().system().equals(key)))
            throw new IllegalArgumentException("Invalid system bodies");
        if (bodies.stream().map(BodyDescriptor::key).distinct().count() != bodies.size())
            throw new IllegalArgumentException("Duplicate bodies");
    }
}

