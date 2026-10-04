package dev.heiko.universe.lod;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static dev.heiko.universe.lod.LodScheduler.Priority.*;

class LodSchedulerTest {
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    @Test void equalButDifferentTaskCannotReleaseWorkerSlot() {
        var s = new LodScheduler<String, String>(2, 1, 20);
        s.subscribe("p", 1, a, MAP);
        var real = s.poll().orElseThrow();
        var fake = new LodScheduler.Task<>(real.version(), real.generation(),
                real.priority(), real.cancelled());
        assertEquals(real, fake); assertNotSame(real, fake);
        s.subscribe("q", 1, b, MAP);
        assertFalse(s.finish(fake, "fake", 1));
        assertEquals(1, s.runningCount());
        assertTrue(s.poll().isEmpty());
        assertTrue(s.finish(real, "real", 1));
        assertEquals("q", s.poll().orElseThrow().version().key());
    }

    @Test void priorityPromotionDoesNotDuplicateMembershipOrCancelRunningWork() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(2, 1, 20, 2, 2, 1, 2));
        s.subscribe("p", 1, a, PREFETCH);
        s.subscribe("q", 1, b, MAP);
        assertTrue(s.subscribe("p", 1, a, ARRIVAL)); // Queue and membership caps full.
        assertEquals(2, s.queuedCount()); assertEquals(2, s.membershipCount());
        var promoted = s.poll().orElseThrow();
        assertEquals("p", promoted.version().key()); assertEquals(ARRIVAL, promoted.priority());
        assertTrue(s.finish(promoted, "p", 1));
        var running = s.poll().orElseThrow();
        assertTrue(s.subscribe("q", 1, b, ARRIVAL));
        assertFalse(running.isCancelled()); assertEquals(1, s.runningCount());
        assertEquals(2, s.membershipCount()); assertEquals(0, s.queuedCount());
        assertFalse(s.finish(running, null, 0));
        assertTrue(s.retry("q"));
        var retried = s.poll().orElseThrow();
        assertEquals(ARRIVAL, retried.priority());
        assertTrue(s.finish(retried, "q", 1));

        var shared = new LodScheduler<String, String>(new LodScheduler.Limits(2, 1, 20, 2, 3, 2, 2));
        shared.subscribe("p", 1, a, PREFETCH);
        shared.subscribe("p", 1, b, ARRIVAL);
        shared.subscribe("q", 1, a, MAP);
        shared.unsubscribe("p", b); // Removed urgency must not persist.
        var q = shared.poll().orElseThrow();
        assertEquals("q", q.version().key());
        assertTrue(shared.finish(q, "q", 1));
        shared.subscribe("p", 1, b, VISIBLE);
        shared.subscribe("p", 1, a, ARRIVAL);
        shared.subscribe("p", 1, a, PREFETCH); // Latest priority replaces a's ARRIVAL.
        var p = shared.poll().orElseThrow();
        assertEquals(VISIBLE, p.priority()); // b still demands VISIBLE.
        assertEquals(3, shared.membershipCount());
        shared.subscribe("p", 2, a, PREFETCH);
        assertTrue(p.isCancelled());
        assertFalse(shared.finish(p, "old", 1));
        var upgraded = shared.poll().orElseThrow();
        assertEquals(VISIBLE, upgraded.priority()); // b's demand survives revision upgrade.
        assertEquals(3, shared.membershipCount());
        assertTrue(shared.finish(upgraded, "new", 1));
    }
    @Test void thousandFailuresCannotGrowDemandBeyondCap() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(1, 1, 0, 3, 3, 1, 2));
        for (int i = 0; i < 1000; i++) {
            boolean accepted = s.subscribe("p" + i, 1, a, MAP);
            assertEquals(i < 3, accepted);
            if (accepted) assertFalse(s.finish(s.poll().orElseThrow(), i % 2 == 0 ? null : "oversize", 1));
            assertTrue(s.demandCount() <= 3);
            assertTrue(s.membershipCount() <= 3);
        }
        for (int i = 0; i < 1000; i++) {
            assertTrue(s.retry("p0"));
            assertFalse(s.finish(s.poll().orElseThrow(), "oversize", 1));
        }
        assertEquals(3, s.demandCount()); assertEquals(3, s.membershipCount());
        s.unsubscribe("p0", a);
        assertTrue(s.subscribe("released", 1, a, MAP));
        assertEquals(3, s.demandCount());
    }

    @Test void membershipCapsIdempotenceUpgradeAndRelease() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(4, 2, 20, 4, 3, 2, 4));
        assertTrue(s.subscribe("p", 1, a, MAP));
        assertTrue(s.subscribe("p", 1, b, MAP));
        var old = s.poll().orElseThrow();
        UUID third = UUID.randomUUID();
        assertFalse(s.subscribe("p", 2, third, ARRIVAL)); // Per-key cap, no cancellation.
        assertFalse(old.isCancelled());
        assertTrue(s.subscribe("q", 1, a, MAP));
        assertTrue(s.subscribe("p", 1, a, MAP)); // Idempotent at both caps.
        assertFalse(s.subscribe("q", 2, b, MAP)); // Total memberships cap.
        assertFalse(s.subscribe("new", 1, third, MAP));
        assertTrue(s.subscribe("p", 2, a, ARRIVAL)); // Transfer, no double count.
        assertTrue(old.isCancelled());
        assertEquals(3, s.membershipCount()); assertEquals(2, s.subscriberCount("p"));
        assertFalse(s.finish(old, "old", 1));
        var current = s.poll().orElseThrow();
        assertEquals(old.generation() + 2, current.generation()); // Only q + accepted upgrade.
        s.unsubscribe("p", third); assertEquals(3, s.membershipCount());
        s.unsubscribe("p", b); s.unsubscribe("q", a);
        assertEquals(1, s.membershipCount()); assertEquals(1, s.demandCount());
        assertTrue(s.subscribe("p", 2, third, MAP));
        assertTrue(s.finish(current, "current", 1));
    }

    @Test void thousandSubscribersCannotExceedFanoutCap() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(1, 1, 0, 1, 10, 4, 1));
        for (int i = 0; i < 1000; i++)
            assertEquals(i < 4, s.subscribe("p", 1, new UUID(0, i), MAP));
        assertEquals(4, s.membershipCount()); assertEquals(4, s.subscriberCount("p"));
    }

    @Test void zeroSizedCacheEntriesAreBoundedAndPinnedBackpressureIsRecoverable() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(2, 1, 100, 4, 4, 1, 2));
        for (String key : new String[]{"p", "q"}) {
            assertTrue(s.subscribe(key, 1, a, MAP));
            assertTrue(s.finish(s.poll().orElseThrow(), key, 0));
        }
        assertTrue(s.subscribe("r", 1, a, MAP));
        assertFalse(s.finish(s.poll().orElseThrow(), "r", 0));
        assertEquals(2, s.cachedEntryCount()); assertEquals(2, s.cachedBytes());
        s.unsubscribe("p", a); assertTrue(s.retry("r"));
        assertTrue(s.finish(s.poll().orElseThrow(), "r", 0));
        assertTrue(s.cached("p", 1).isEmpty());
        s.unsubscribe("q", a); s.unsubscribe("r", a);
        for (int i = 0; i < 1000; i++) {
            String key = "zero" + i;
            assertTrue(s.subscribe(key, 1, a, MAP));
            assertTrue(s.finish(s.poll().orElseThrow(), key, 0));
            s.unsubscribe(key, a);
            assertEquals(2, s.cachedEntryCount());
        }
    }

    @Test void failedByteAndEntryPreflightDoesNotPartiallyEvict() {
        var s = new LodScheduler<String, String>(new LodScheduler.Limits(2, 1, 10, 4, 4, 1, 2));
        s.subscribe("unused", 1, a, MAP);
        assertTrue(s.finish(s.poll().orElseThrow(), "unused", 1)); s.unsubscribe("unused", a);
        s.subscribe("pinned", 1, a, MAP);
        assertTrue(s.finish(s.poll().orElseThrow(), "pinned", 8));
        s.subscribe("incoming", 1, a, MAP);
        assertFalse(s.finish(s.poll().orElseThrow(), "incoming", 3));
        assertEquals("unused", s.cached("unused", 1).orElseThrow());
        assertEquals(9, s.cachedBytes()); assertEquals(2, s.cachedEntryCount());
        s.unsubscribe("pinned", a); assertTrue(s.retry("incoming"));
        assertTrue(s.finish(s.poll().orElseThrow(), "incoming", 3));
        // Reading unused above refreshed its LRU position. Evicting the older eight-byte
        // entry alone satisfies both limits; the newer one-byte entry must survive.
        assertEquals("unused", s.cached("unused", 1).orElseThrow());
        assertTrue(s.cached("pinned", 1).isEmpty());
        assertEquals(4, s.cachedBytes());
        assertEquals(2, s.cachedEntryCount());
    }

    @Test void absoluteLimitsCannotBeDisabledByHostOptions() {
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(4097, 1, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 65, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, Long.MAX_VALUE, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, 0, 65537, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, 0, 1, 262145, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, 0, 1, 1, 4097, 1));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, 0, 1, 1, 1, 65537));
        assertThrows(IllegalArgumentException.class, () -> new LodScheduler.Limits(1, 1, 0, 1, 1, 1, 0));
        var defaults = new LodScheduler<String, String>(1, 1, 0).limits();
        assertEquals(4096, defaults.demandRecords()); assertEquals(4096, defaults.cacheEntries());
    }
    @Test void sharedDemandAndLastSubscriberCancellation() {
        var s = new LodScheduler<String, String>(2, 1, 20);
        assertTrue(s.subscribe("p", 1, a, NEARBY));
        assertTrue(s.subscribe("p", 1, b, NEARBY));
        assertEquals(1, s.queuedCount());
        var task = s.poll().orElseThrow();
        s.unsubscribe("p", a); assertFalse(task.isCancelled());
        s.unsubscribe("p", b); assertTrue(task.isCancelled());
        assertTrue(s.subscribe("q", 1, a, ARRIVAL));
        assertTrue(s.poll().isEmpty()); // Cancelled worker still consumes a slot.
        assertFalse(s.finish(task, "late", 4));
        assertTrue(s.poll().isPresent());
        assertTrue(s.cached("p", 1).isEmpty());
    }
    @Test void supersededRevisionAndGenerationCannotPublish() {
        var s = new LodScheduler<String, String>(2, 2, 20);
        s.subscribe("p", 1, a, MAP);
        var old = s.poll().orElseThrow();
        assertTrue(s.subscribe("p", 2, b, VISIBLE));
        assertEquals(2, s.subscriberCount("p"));
        assertFalse(s.subscribe("p", 1, a, ARRIVAL));
        var current = s.poll().orElseThrow();
        assertFalse(s.finish(old, "old", 4));
        assertTrue(s.finish(current, "new", 4));
        s.unsubscribe("p", a); s.unsubscribe("p", b);
        s.subscribe("q", 1, a, MAP);
        var failed = s.poll().orElseThrow();
        assertFalse(s.finish(failed, null, 0));
        assertTrue(s.retry("q"));
        var retried = s.poll().orElseThrow();
        assertNotEquals(failed.generation(), retried.generation());
        assertFalse(s.finish(failed, "late", 1));
        assertTrue(s.finish(retried, "ok", 1));
    }
    @Test void boundedQueueUnderLoadAndRejectedUpgradeKeepsDemand() {
        var s = new LodScheduler<String, String>(8, 2, 20);
        for (int i = 0; i < 1000; i++)
            assertEquals(i < 8, s.subscribe("p" + i, 1, a, PREFETCH));
        assertEquals(8, s.queuedCount());
        var task = s.poll().orElseThrow();
        s.subscribe("fill", 1, a, PREFETCH);
        assertFalse(s.subscribe(task.version().key(), 2, b, ARRIVAL));
        assertEquals(1, s.subscriberCount(task.version().key()));
        assertFalse(task.isCancelled());
        assertTrue(s.finish(task, "ok", 1));
    }
    @Test void byteCachePinsActiveDataAndEvictsUnusedEntries() {
        var s = new LodScheduler<String, String>(2, 1, 10);
        s.subscribe("p", 1, a, NEARBY);
        assertTrue(s.finish(s.poll().orElseThrow(), "p", 6));
        s.subscribe("q", 1, b, NEARBY);
        assertFalse(s.finish(s.poll().orElseThrow(), "q", 6));
        assertEquals(6, s.cachedBytes());
        s.unsubscribe("p", a);
        assertTrue(s.retry("q"));
        assertTrue(s.finish(s.poll().orElseThrow(), "q", 6));
        assertTrue(s.cached("p", 1).isEmpty());
        assertEquals("q", s.cached("q", 1).orElseThrow());
        assertEquals(6, s.cachedBytes());
    }
    @Test void priorityAndOversizeBoundaries() {
        var s = new LodScheduler<String, String>(2, 1, 1);
        s.subscribe("far", 1, a, PREFETCH); s.subscribe("arrival", 1, b, ARRIVAL);
        var task = s.poll().orElseThrow();
        assertEquals("arrival", task.version().key());
        assertFalse(s.finish(task, "too big", 2));
        assertEquals(0, s.cachedBytes());
        assertTrue(s.retry("arrival"));
        assertTrue(s.finish(s.poll().orElseThrow(), "empty", 0));
        assertEquals(1, s.cachedBytes());
    }
}
