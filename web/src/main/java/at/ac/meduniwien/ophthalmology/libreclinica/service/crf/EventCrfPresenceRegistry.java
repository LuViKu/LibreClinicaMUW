/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Phase E.6 crf-entry-advanced — in-memory soft-lock + heartbeat
 * registry.
 *
 * <p>Reviewer flag from the playbook: the {@code service/crf/} package
 * did not exist before this PR (only {@code service/extract/} +
 * {@code service/auth/} +1/2 others were present). This class creates
 * the package + carries the Spring {@code @Service} stereotype so
 * Phase E.4's component-scan picks it up without an explicit XML
 * registration. The build-playbook called for a singleton wiring in
 * {@code applicationContext-web-beans.xml}; {@code @Service} +
 * existing {@code <context:component-scan>} on the controller package
 * is functionally equivalent and pre-existing in the codebase.
 *
 * <p><strong>Concurrency:</strong> all state lives in a
 * {@link ConcurrentHashMap}. The registry is in-memory only — a
 * server restart resets all presence entries. Sufficient for the
 * UX-hint purpose (no hard lock; concurrent saves still permitted).
 *
 * <p><strong>TTL:</strong> entries older than {@link #TTL_SECONDS}
 * are treated as stale on read. {@link #freshestEntry} purges stale
 * entries lazily on access so the registry doesn't need a sweeper
 * thread.
 *
 * <p><strong>Contract:</strong>
 * <ul>
 *   <li>{@link #heartbeat(int, int, String)} — record/refresh the
 *       caller's presence on a given event_crf and return the
 *       freshest non-stale entry (which may be the caller's own).</li>
 *   <li>{@link #freshestEntry(int)} — read-only lookup of the
 *       freshest non-stale entry; returns {@link Optional#empty()}
 *       on cold probe.</li>
 *   <li>{@link #clear()} — test seam.</li>
 * </ul>
 */
@Service
public class EventCrfPresenceRegistry {

    /** Presence-entry TTL in seconds. Echoed to the SPA via {@code
     *  EventCrfLockProbeDto.ttlSeconds} so the SPA schedules its
     *  heartbeat with margin (< TTL / 2). */
    public static final int TTL_SECONDS = 60;

    /**
     * One presence entry per (eventCrfId, userId) pair. The freshest
     * entry across all userIds for a given eventCrfId drives the
     * SPA's banner. We key by userId (not session) so the same user
     * editing from two tabs doesn't see themselves on the banner.
     */
    public record PresenceEntry(int userId, String userName, Instant lastSeenAt) {}

    private final Clock clock;
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, PresenceEntry>> byEventCrf;

    public EventCrfPresenceRegistry() {
        this(Clock.systemUTC());
    }

    /** Test seam — inject a tickable {@link Clock} for TTL-rollover tests. */
    public EventCrfPresenceRegistry(Clock clock) {
        this.clock = clock;
        this.byEventCrf = new ConcurrentHashMap<>();
    }

    /**
     * Record a heartbeat for {@code (eventCrfId, userId)}. The
     * caller's entry is upserted; the returned entry is the freshest
     * non-stale entry across <em>all</em> users on the event_crf
     * (which may or may not be the caller's own). The SPA compares
     * the returned {@code userId} to its session user to render the
     * banner.
     *
     * @return the freshest non-stale entry, never {@code null}
     */
    public PresenceEntry heartbeat(int eventCrfId, int userId, String userName) {
        Instant now = clock.instant();
        PresenceEntry mine = new PresenceEntry(userId, userName == null ? "" : userName, now);
        ConcurrentHashMap<Integer, PresenceEntry> entries =
                byEventCrf.computeIfAbsent(eventCrfId, k -> new ConcurrentHashMap<>());
        entries.put(userId, mine);
        purgeStale(entries, now);
        // Pick the freshest remaining entry (which may differ from
        // 'mine' if another user just heartbeated this very ms).
        return entries.values().stream()
                .max((a, b) -> a.lastSeenAt.compareTo(b.lastSeenAt))
                .orElse(mine);
    }

    /**
     * Read-only probe: returns the freshest non-stale entry for
     * {@code eventCrfId}, or {@link Optional#empty()} if every entry
     * is older than the TTL (or none exist).
     */
    public Optional<PresenceEntry> freshestEntry(int eventCrfId) {
        ConcurrentHashMap<Integer, PresenceEntry> entries = byEventCrf.get(eventCrfId);
        if (entries == null || entries.isEmpty()) return Optional.empty();
        Instant now = clock.instant();
        purgeStale(entries, now);
        return entries.values().stream()
                .max((a, b) -> a.lastSeenAt.compareTo(b.lastSeenAt));
    }

    /** Drop the caller's entry. The SPA could fire this on unload, but the
     *  current contract relies on TTL expiry to clear silent leavers. */
    public void release(int eventCrfId, int userId) {
        ConcurrentHashMap<Integer, PresenceEntry> entries = byEventCrf.get(eventCrfId);
        if (entries != null) entries.remove(userId);
    }

    /** Test seam — drop all state. Production code never calls this. */
    public void clear() {
        byEventCrf.clear();
    }

    private void purgeStale(Map<Integer, PresenceEntry> entries, Instant now) {
        Iterator<Map.Entry<Integer, PresenceEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            PresenceEntry e = it.next().getValue();
            if (now.toEpochMilli() - e.lastSeenAt.toEpochMilli() > TTL_SECONDS * 1000L) {
                it.remove();
            }
        }
    }
}
