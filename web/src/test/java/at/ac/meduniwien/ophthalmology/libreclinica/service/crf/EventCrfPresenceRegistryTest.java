/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Phase E.6 crf-entry-advanced — unit tests for the in-memory
 * presence registry. Covers (1) cold probe, (2) self-heartbeat,
 * (3) other-user collision detection, (4) TTL expiry, and (5)
 * thread-safety of concurrent heartbeats.
 */
class EventCrfPresenceRegistryTest {

    private static final int E1 = 42;

    @Test
    void coldProbeReturnsEmpty() {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        assertTrue(r.freshestEntry(E1).isEmpty());
    }

    @Test
    void heartbeatRecordsPresence() {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        EventCrfPresenceRegistry.PresenceEntry e = r.heartbeat(E1, 7, "alice");
        assertEquals(7, e.userId());
        assertEquals("alice", e.userName());
        assertEquals(7, r.freshestEntry(E1).orElseThrow().userId());
    }

    @Test
    void freshestEntryPicksTheLatestAcrossUsers() throws Exception {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        r.heartbeat(E1, 1, "alice");
        Thread.sleep(2);
        r.heartbeat(E1, 2, "bob");
        Optional<EventCrfPresenceRegistry.PresenceEntry> fresh = r.freshestEntry(E1);
        assertTrue(fresh.isPresent());
        assertEquals(2, fresh.get().userId());
        assertEquals("bob", fresh.get().userName());
    }

    @Test
    void heartbeatReturnsTheGlobalFreshest() throws Exception {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        r.heartbeat(E1, 1, "alice");
        Thread.sleep(2);
        // bob heartbeats — return value is bob's own entry (which is
        // freshest because it just landed).
        EventCrfPresenceRegistry.PresenceEntry e = r.heartbeat(E1, 2, "bob");
        assertEquals(2, e.userId());
    }

    @Test
    void ttlExpiresStaleEntries() {
        // Tickable clock advances 120s on second read so the 60s TTL kicks in.
        Instant start = Instant.parse("2026-06-05T10:00:00Z");
        Clock[] holder = new Clock[1];
        holder[0] = Clock.fixed(start, ZoneId.of("UTC"));
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry(new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return holder[0].instant(); }
        });
        r.heartbeat(E1, 7, "alice");
        // Advance the clock past the TTL — alice's entry should be pruned.
        holder[0] = Clock.fixed(start.plusSeconds(EventCrfPresenceRegistry.TTL_SECONDS + 5),
                ZoneId.of("UTC"));
        assertTrue(r.freshestEntry(E1).isEmpty(),
                "freshestEntry should return empty once the only entry is stale");
    }

    @Test
    void releaseDropsTheUserEntry() {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        r.heartbeat(E1, 1, "alice");
        r.heartbeat(E1, 2, "bob");
        r.release(E1, 2);
        Optional<EventCrfPresenceRegistry.PresenceEntry> fresh = r.freshestEntry(E1);
        assertTrue(fresh.isPresent());
        assertEquals(1, fresh.get().userId());
    }

    @Test
    void concurrentHeartbeatsDontLoseUpdates() throws Exception {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        int users = 16;
        int iters = 200;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        try {
            for (int u = 1; u <= users; u++) {
                final int uid = u;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iters; i++) {
                            r.heartbeat(E1, uid, "u" + uid);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all heartbeats finished");
            Optional<EventCrfPresenceRegistry.PresenceEntry> fresh = r.freshestEntry(E1);
            assertTrue(fresh.isPresent());
            assertNotNull(fresh.get().userName());
            assertFalse(fresh.get().userName().isBlank());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clearDropsAllState() {
        EventCrfPresenceRegistry r = new EventCrfPresenceRegistry();
        r.heartbeat(E1, 1, "alice");
        r.clear();
        assertTrue(r.freshestEntry(E1).isEmpty());
    }
}
