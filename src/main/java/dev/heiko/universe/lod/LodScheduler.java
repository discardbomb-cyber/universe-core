package dev.heiko.universe.lod;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe metadata only. Workers receive immutable snapshots from the caller,
 * never live Minecraft objects. Completion publishes a value; rendering/world application
 * belongs to the caller's game thread. A worker must acknowledge cancellation via finish. */
public final class LodScheduler<K, V> {
    /** Absolute ceilings bound metadata, not the heap retained by caller-owned K/V objects. */
    public record Limits(int queue, int workers, long cacheBytes, int demandRecords,
                         int memberships, int subscribersPerKey, int cacheEntries) {
        public Limits {
            if (queue < 1 || queue > 4096 || workers < 1 || workers > 64
                    || cacheBytes < 0 || cacheBytes > 1_073_741_824L
                    || demandRecords < 1 || demandRecords > 65536
                    || memberships < 1 || memberships > 262144
                    || subscribersPerKey < 1 || subscribersPerKey > 4096
                    || cacheEntries < 1 || cacheEntries > 65536)
                throw new IllegalArgumentException("LOD limits outside absolute bounds");
        }
    }
    public enum Priority { ARRIVAL, NEARBY, VISIBLE, MAP, PREFETCH }
    public record Version<K>(K key, long revision) {}
    public record Task<K>(Version<K> version, long generation, Priority priority,
                          AtomicBoolean cancelled) {
        public boolean isCancelled() { return cancelled.get(); }
    }
    private final class Entry {
        final Task<K> task;
        Priority priority;
        final Map<UUID, Priority> subscribers = new HashMap<>();
        Entry(Task<K> task) { this.task = task; this.priority = task.priority(); }
    }
    private record Cached<V>(V value, long bytes) {}
    private final int queueLimit, workerLimit;
    private final long cacheLimit;
    private final Limits limits;
    private int memberships;
    private long generation, cacheBytes;
    private final Map<K, Entry> demand = new HashMap<>();
    private final Set<Task<K>> running = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PriorityQueue<Task<K>> queue = new PriorityQueue<>(
            Comparator.<Task<K>, Priority>comparing(Task::priority).thenComparingLong(Task::generation));
    private final LinkedHashMap<Version<K>, Cached<V>> cache = new LinkedHashMap<>(16, .75f, true);

    public LodScheduler(int queueLimit, int workerLimit, long cacheLimit) {
        this(new Limits(queueLimit, workerLimit, cacheLimit, 4096, 16384, 256, 4096));
    }

    public LodScheduler(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
        this.queueLimit = limits.queue(); this.workerLimit = limits.workers();
        this.cacheLimit = limits.cacheBytes();
    }

    /** False applies backpressure; no existing demand is destroyed on rejection.
     * All subscribers to a key follow its newest accepted revision. Each subscriber's
     * latest accepted priority replaces its previous one; queue priority is the strongest
     * currently subscribed priority. Priority changes preserve a running task's identity,
     * and are reflected in its next retry rather than cancelling computation. */
    public synchronized boolean subscribe(K key, long revision, UUID subscriber, Priority priority) {
        Objects.requireNonNull(key); Objects.requireNonNull(subscriber); Objects.requireNonNull(priority);
        if (revision < 0) throw new IllegalArgumentException("negative revision");
        Entry old = demand.get(key);
        if (old != null && revision < old.task.version().revision()) return false;
        boolean newSubscriber = old == null || !old.subscribers.containsKey(subscriber);
        if (old == null && demand.size() >= limits.demandRecords()) return false;
        if (newSubscriber && (memberships >= limits.memberships()
                || (old != null && old.subscribers.size() >= limits.subscribersPerKey()))) return false;
        if (old != null && revision == old.task.version().revision()) {
            old.subscribers.put(subscriber, priority);
            if (newSubscriber) memberships++;
            refreshPriority(key, old);
            return true;
        }
        Version<K> version = new Version<>(key, revision);
        boolean cached = cache.containsKey(version);
        boolean replacesQueued = old != null && queue.contains(old.task);
        if (!cached && queue.size() - (replacesQueued ? 1 : 0) >= queueLimit) return false;
        Map<UUID, Priority> acceptedSubscribers = new HashMap<>();
        if (old != null) acceptedSubscribers.putAll(old.subscribers);
        acceptedSubscribers.put(subscriber, priority);
        Priority acceptedPriority = strongest(acceptedSubscribers);
        Task<K> task = new Task<>(version, ++generation, acceptedPriority, new AtomicBoolean());
        Entry entry = new Entry(task);
        if (old != null) {
            old.task.cancelled().set(true); queue.remove(old.task);
        }
        entry.subscribers.putAll(acceptedSubscribers); demand.put(key, entry);
        if (newSubscriber) memberships++;
        if (!cached) queue.add(task);
        return true;
    }

    public synchronized void unsubscribe(K key, UUID subscriber) {
        Entry entry = demand.get(key);
        if (entry == null) return;
        if (entry.subscribers.remove(subscriber) == null) return;
        memberships--;
        if (entry.subscribers.isEmpty()) {
            demand.remove(key); entry.task.cancelled().set(true); queue.remove(entry.task);
        } else refreshPriority(key, entry);
    }

    private Priority strongest(Map<UUID, Priority> subscribers) {
        return subscribers.values().stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    /** Queue order reflects current memberships. Running work is never replaced for priority alone. */
    private void refreshPriority(K key, Entry entry) {
        Priority priority = strongest(entry.subscribers);
        if (priority == entry.priority) return;
        entry.priority = priority;
        if (queue.remove(entry.task)) {
            Task<K> replacementTask = new Task<>(entry.task.version(), ++generation,
                    priority, new AtomicBoolean());
            Entry replacement = new Entry(replacementTask);
            replacement.subscribers.putAll(entry.subscribers);
            entry.task.cancelled().set(true);
            demand.put(key, replacement);
            queue.add(replacementTask);
        }
    }

    /** Running slots remain occupied until even a cancelled worker acknowledges finish. */
    public synchronized Optional<Task<K>> poll() {
        if (running.size() >= workerLimit) return Optional.empty();
        Task<K> task = queue.poll();
        if (task == null) return Optional.empty();
        running.add(task); return Optional.of(task);
    }

    /** Returns false for cancellation, stale identity/revision/generation or cache backpressure.
     * Null value acknowledges failure; caller decides whether to retry. */
    public synchronized boolean finish(Task<K> task, V value, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative size");
        if (!running.remove(task)) return false;
        Entry entry = demand.get(task.version().key());
        if (entry == null || entry.task != task || task.isCancelled() || value == null) return false;
        // Charge at least one byte so zero-sized values cannot create an unbounded cache.
        bytes = Math.max(1, bytes);
        if (bytes > cacheLimit) return false;
        // Active cache entries are pinned. Preflight prevents partial eviction on rejection.
        long reclaimable = 0;
        int removable = 0;
        if (cacheBytes > cacheLimit - bytes || cache.size() >= limits.cacheEntries()) {
            for (var candidate : cache.entrySet()) {
                if (!pinned(candidate.getKey())) {
                    reclaimable += candidate.getValue().bytes(); removable++;
                }
            }
            if (cacheBytes - reclaimable > cacheLimit - bytes
                    || cache.size() - removable >= limits.cacheEntries()) return false;
        }
        Iterator<Map.Entry<Version<K>, Cached<V>>> it = cache.entrySet().iterator();
        while ((cacheBytes > cacheLimit - bytes || cache.size() >= limits.cacheEntries()) && it.hasNext()) {
            var candidate = it.next();
            if (!pinned(candidate.getKey())) { cacheBytes -= candidate.getValue().bytes(); it.remove(); }
        }
        cache.put(task.version(), new Cached<>(value, bytes)); cacheBytes += bytes;
        return true;
    }

    private boolean pinned(Version<K> version) {
        Entry entry = demand.get(version.key());
        return entry != null && entry.task.version().equals(version);
    }

    /** Retry an accepted demand after failed/cancelled computation or cache backpressure. */
    public synchronized boolean retry(K key) {
        Entry entry = demand.get(key);
        if (entry == null || queue.contains(entry.task) || running.contains(entry.task)
                || cache.containsKey(entry.task.version()) || queue.size() >= queueLimit) return false;
        Task<K> replacement = new Task<>(entry.task.version(), ++generation,
                entry.priority, new AtomicBoolean());
        Entry replacementEntry = new Entry(replacement);
        replacementEntry.subscribers.putAll(entry.subscribers);
        demand.put(key, replacementEntry);
        queue.add(replacement); return true;
    }

    public synchronized Optional<V> cached(K key, long revision) {
        Cached<V> found = cache.get(new Version<>(key, revision));
        return found == null ? Optional.empty() : Optional.of(found.value());
    }
    public synchronized int queuedCount() { return queue.size(); }
    public synchronized int runningCount() { return running.size(); }
    public synchronized int subscriberCount(K key) {
        Entry entry = demand.get(key); return entry == null ? 0 : entry.subscribers.size();
    }
    public synchronized long cachedBytes() { return cacheBytes; }
    public synchronized int demandCount() { return demand.size(); }
    public synchronized int membershipCount() { return memberships; }
    public synchronized int cachedEntryCount() { return cache.size(); }
    public Limits limits() { return limits; }
}
