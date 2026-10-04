package dev.heiko.universe.api.material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable bounded catalog. Lookup is expected O(1); ordered traversal is canonical
 * namespace/path order regardless of registration order. No Minecraft classes are used.
 * List positions are transient and must never be persisted as material identities.
 * Frozen catalogs can be shared between threads; builders are thread confined.
 */
public final class MaterialCatalog {
    public static final int DEFAULT_CAPACITY = 65_536;
    public static final int MAX_CAPACITY = 1_000_000;
    private final Map<MaterialId, MaterialDefinition> byId;
    private final List<MaterialDefinition> definitions;

    private MaterialCatalog(Map<MaterialId, MaterialDefinition> entries) {
        definitions = List.copyOf(new TreeMap<>(entries).values());
        byId = Map.copyOf(entries);
    }

    public static Builder builder() { return new Builder(DEFAULT_CAPACITY); }
    public static Builder builder(int capacity) { return new Builder(capacity); }
    public int size() { return definitions.size(); }
    public List<MaterialDefinition> definitions() { return definitions; }
    public Optional<MaterialDefinition> find(MaterialId id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public static final class Builder {
        private final int capacity;
        private final Map<MaterialId, MaterialDefinition> entries = new HashMap<>();
        private MaterialCatalog frozen;

        private Builder(int capacity) {
            if (capacity < 1 || capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("Capacity must be 1.." + MAX_CAPACITY);
            }
            this.capacity = capacity;
        }

        /** Rejects duplicate IDs, even when definitions are equal; never overwrites. */
        public Builder register(MaterialDefinition definition) {
            if (frozen != null) throw new IllegalStateException("Catalog is frozen");
            Objects.requireNonNull(definition, "definition");
            if (entries.containsKey(definition.id())) {
                throw new IllegalArgumentException("Duplicate material: " + definition.id());
            }
            if (entries.size() >= capacity) throw new IllegalStateException("Catalog capacity reached");
            entries.put(definition.id(), definition);
            return this;
        }

        /** Idempotent: the first call closes registration and publishes an immutable snapshot. */
        public MaterialCatalog freeze() {
            if (frozen == null) frozen = new MaterialCatalog(entries);
            return frozen;
        }
    }
}
