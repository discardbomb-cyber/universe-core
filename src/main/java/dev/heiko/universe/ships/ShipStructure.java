package dev.heiko.universe.ships;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Event-driven section summaries, not canonical block storage or a physical hull. */
public final class ShipStructure {
    public record Summary(long revision, int occupiedBlocks, double mass) {
        public Summary {
            if (revision < 0 || occupiedBlocks < 0 || occupiedBlocks > ShipSection.VOLUME
                    || !Double.isFinite(mass) || mass < 0 || (occupiedBlocks == 0 && mass != 0))
                throw new IllegalArgumentException("Invalid section summary");
        }
    }
    private final ShipId shipId;
    private final int sectionLimit;
    // Empty summaries are tombstones: deleting a section must not resurrect a stale update.
    private final Map<ShipSection, Summary> summaries = new HashMap<>();
    private long occupiedBlocks;
    private double mass;
    /** Deterministic work counts; no timers or block accesses exist in this model. */
    public record WorkCounters(long sectionLookups, long aggregateUpdates, long snapshotEntriesCopied) {}
    private long sectionLookups;
    private long aggregateUpdates;
    private long snapshotEntriesCopied;

    public ShipStructure(ShipId shipId, int sectionLimit) {
        this.shipId = Objects.requireNonNull(shipId);
        if (sectionLimit <= 0) throw new IllegalArgumentException("Section limit");
        this.sectionLimit = sectionLimit;
    }
    /** Exact duplicate is a no-op; conflicting or older revisions are rejected. */
    public synchronized boolean update(ShipSection section, Summary next) {
        Objects.requireNonNull(section); Objects.requireNonNull(next);
        if (!shipId.equals(section.shipId())) throw new IllegalArgumentException("Wrong ship");
        sectionLookups++;
        Summary previous = summaries.get(section);
        if (next.equals(previous)) return false;
        if (previous != null && next.revision <= previous.revision) throw new IllegalArgumentException("Stale revision");
        if (previous == null && summaries.size() >= sectionLimit) throw new IllegalStateException("Section capacity");
        double nextMass = mass - (previous == null ? 0 : previous.mass) + next.mass;
        long nextBlocks = Math.addExact(occupiedBlocks - (previous == null ? 0 : previous.occupiedBlocks), next.occupiedBlocks);
        if (!Double.isFinite(nextMass) || nextMass < 0) throw new IllegalArgumentException("Aggregate mass range");
        summaries.put(section,next); occupiedBlocks=nextBlocks;mass=nextMass;
        aggregateUpdates++;
        return true;
    }
    public synchronized long occupiedBlocks() { return occupiedBlocks; }
    public synchronized double mass() { return mass; }
    public synchronized int knownSections() { return summaries.size(); }
    public synchronized WorkCounters workCounters() {
        return new WorkCounters(sectionLookups, aggregateUpdates, snapshotEntriesCopied);
    }
    /** Explicit snapshot cost is proportional to section count; never call every movement tick. */
    public synchronized Map<ShipSection, Summary> snapshot() {
        Map<ShipSection, Summary> copy = Map.copyOf(summaries);
        snapshotEntriesCopied += copy.size();
        return copy;
    }
}
