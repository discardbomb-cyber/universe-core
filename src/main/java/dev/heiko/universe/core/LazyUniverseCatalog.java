package dev.heiko.universe.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded deterministic metadata skeleton, not the final spatial density generator.
 * No chunk access, persistence, neighbors or ticking. Sector expansion is at most 8 * 63 bodies.
 */
public final class LazyUniverseCatalog {
    private final long universeSeed;
    private final Versions versions;
    private final int capacity;
    private final LinkedHashMap<SectorKey, SectorDescriptor> cache = new LinkedHashMap<>(16, .75f, true);
    public LazyUniverseCatalog(long universeSeed, Versions versions, int capacity) {
        this.universeSeed = universeSeed; this.versions = Objects.requireNonNull(versions);
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Sector cache capacity must be 1..4096");
        this.capacity = capacity;
    }
    public synchronized int cachedSectorCount() { return cache.size(); }
    public synchronized void clearCache() { cache.clear(); }
    public synchronized SectorDescriptor sector(SectorKey key) {
        Objects.requireNonNull(key);
        var existing = cache.get(key);
        if (existing != null) return existing;
        var generated = generate(key);
        cache.put(key, generated);
        if (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
        return generated;
    }
    public Optional<SystemDescriptor> system(SystemKey key) {
        Objects.requireNonNull(key);
        return sector(key.sector()).systems().stream().filter(s -> s.key().equals(key)).findFirst();
    }
    public Optional<BodyDescriptor> body(BodyKey key) {
        Objects.requireNonNull(key);
        return system(key.system()).flatMap(s -> s.bodies().stream().filter(b -> b.key().equals(key)).findFirst());
    }
    private long seed(SectorKey key, String field, int... slots) {
        return StableSeed.derive(universeSeed, versions, key, field, slots);
    }
    private int count(SectorKey key, String field, int bound, int... slots) {
        return (int) Math.floorMod(seed(key, field, slots), (long) bound);
    }
    private SectorDescriptor generate(SectorKey key) {
        if (!GalaxyProfile.forRealm(key.realm()).contains(key))
            return new SectorDescriptor(key, versions, java.util.List.of());
        var systems = new ArrayList<SystemDescriptor>();
        int systemCount = count(key, "system.count", 9);
        for (int s = 0; s < systemCount; s++) {
            var system = new SystemKey(key, s);
            var bodies = new ArrayList<BodyDescriptor>();
            bodies.add(descriptor(system, BodyKey.Kind.STAR, -1, 0));
            int planets = count(key, "planet.count", 13, s);
            for (int p = 0; p < planets; p++) {
                bodies.add(descriptor(system, BodyKey.Kind.PLANET, -1, p));
                int moons = count(key, "moon.count", 5, s, p);
                for (int m = 0; m < moons; m++) bodies.add(descriptor(system, BodyKey.Kind.MOON, p, m));
            }
            int belts = count(key, "belt.count", 3, s);
            for (int b = 0; b < belts; b++) bodies.add(descriptor(system, BodyKey.Kind.BELT, -1, b));
            systems.add(new SystemDescriptor(system, seed(key, "system.seed", s), versions, bodies));
        }
        return new SectorDescriptor(key, versions, systems);
    }
    private BodyDescriptor descriptor(SystemKey key, BodyKey.Kind kind, int parent, int slot) {
        return new BodyDescriptor(new BodyKey(key, kind, parent, slot),
                seed(key.sector(), "body." + kind.name(), key.slot(), parent, slot), versions);
    }
}
