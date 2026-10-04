package dev.heiko.universe.core;

import java.util.List;
import java.util.Objects;

public record SectorDescriptor(SectorKey key, Versions versions, List<SystemDescriptor> systems) {
    public SectorDescriptor {
        Objects.requireNonNull(key); Objects.requireNonNull(versions); systems = List.copyOf(systems);
        if (systems.size() > 8 || systems.stream().anyMatch(s -> !s.key().sector().equals(key)))
            throw new IllegalArgumentException("Invalid sector systems");
        if (systems.stream().map(SystemDescriptor::key).distinct().count() != systems.size())
            throw new IllegalArgumentException("Duplicate systems");
    }
}

