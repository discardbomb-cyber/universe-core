package dev.heiko.universe.api.planet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded metadata registration, intended for an application's bootstrap phase.
 * Global planet IDs are unique across realms. Freeze prevents every later write.
 * Thread-safe operations; no Minecraft, client, IO or dimension lifecycle calls.
 * This registry does not resolve references or grant surface visitability.
 */
public final class PlanetRegistry {
    public static final int FORMAT_VERSION = 1;
    public static final int DEFAULT_CAPACITY = 5000;
    public static final int MAX_CAPACITY = 65536;

    private final int capacity;
    private final LinkedHashMap<String, PlanetDefinition> definitions = new LinkedHashMap<>();
    private long revision;
    private boolean frozen;

    public PlanetRegistry() { this(DEFAULT_CAPACITY); }

    public PlanetRegistry(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("Capacity must be 1.." + MAX_CAPACITY);
        this.capacity = capacity;
    }

    /** A failed registration leaves content and revision unchanged. */
    public synchronized void register(PlanetDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (frozen) throw new IllegalStateException("Planet registry is frozen");
        if (definitions.containsKey(definition.id()))
            throw new IllegalArgumentException("Duplicate planet ID: " + definition.id());
        if (definitions.size() >= capacity)
            throw new IllegalStateException("Planet registry capacity exhausted");
        definitions.put(definition.id(), definition);
        revision++;
    }

    public synchronized Optional<PlanetDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(PlanetIds.requireValid(id)));
    }

    /** Immutable point-in-time copy in registration order, not a live view. */
    public synchronized List<PlanetDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** Idempotent. Revision counts successful registrations, not freeze calls. */
    public synchronized Snapshot freeze() {
        frozen = true;
        return snapshot();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(FORMAT_VERSION, revision, frozen, definitions());
    }

    public synchronized boolean isFrozen() { return frozen; }
    public synchronized long revision() { return revision; }
    public synchronized int size() { return definitions.size(); }
    public int capacity() { return capacity; }
    public int formatVersion() { return FORMAT_VERSION; }

    /**
     * Exportable metadata only. Version 1 is the only accepted format; restoration,
     * profile resolution and persistence belong to the integrating application.
     */
    public record Snapshot(int formatVersion, long revision, boolean frozen,
                           List<PlanetDefinition> definitions) {
        public Snapshot {
            if (formatVersion != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported planet registry format");
            definitions = List.copyOf(definitions);
            if (definitions.size() > MAX_CAPACITY || revision != definitions.size())
                throw new IllegalArgumentException("Invalid append-only registry revision/size");
            if (definitions.stream().map(PlanetDefinition::id).distinct().count() != definitions.size())
                throw new IllegalArgumentException("Duplicate snapshot planet IDs");
        }
    }
}

