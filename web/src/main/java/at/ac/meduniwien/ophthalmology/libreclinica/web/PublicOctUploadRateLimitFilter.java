/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wave 1B — hand-rolled token-bucket rate limit for the public
 * OCT-upload portal. Caps each client IP at 30 requests/hour against
 * any path under {@code /pages/api/v1/public/oct-upload/}.
 *
 * <p>Why a hand-rolled bucket: the upstream Bucket4j / resilience4j
 * libraries would add a Maven dependency for a one-rule filter; the
 * portal's threat model (institutional reverse proxy is the only gate)
 * is matched by an in-process counter that doesn't need shared state.
 *
 * <p>Refill rate: 1 token / 120 s = 30 tokens / hour. Bursts of up to
 * 30 succeed immediately; subsequent calls wait until the next token
 * refills.
 *
 * <p>Client IP resolution: prefers the first entry in {@code X-Forwarded-For}
 * (the reverse proxy is mandatory for production), falls back to
 * {@code request.getRemoteAddr()} for direct (dev / smoke-test) hits.
 *
 * <p>Idle eviction: a scheduled task drops buckets that haven't seen a
 * request in 1 h so a parade of distinct client IPs can't bloat the
 * map without bound.
 *
 * <p>Wire via {@code SecurityConfig.addFilterBefore(filter, ChannelProcessingFilter.class)}.
 */
@Component
public class PublicOctUploadRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(PublicOctUploadRateLimitFilter.class);

    /** Max tokens per bucket (= initial fill on first miss). */
    static final int MAX_REQUESTS_PER_HOUR = 30;

    /** One token per 2 minutes → 30 tokens / hour. */
    static final long REFILL_INTERVAL_MS = 120_000L;

    /** Buckets older than 1 h with no activity get dropped. */
    static final long IDLE_BUCKET_TTL_MS = 3_600_000L;

    /** Path prefix the filter polices. */
    static final String GUARDED_PREFIX = "/pages/api/v1/public/oct-upload/";

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Token-bucket carrier. Atomics avoid the per-bucket lock that a
     * dedicated synchronization block would introduce; the contention
     * profile here is read-modify-write so AtomicInteger is enough.
     */
    static final class Bucket {
        final AtomicInteger tokens;
        final AtomicLong lastRefillMs;
        final AtomicLong lastTouchedMs;

        Bucket(int initialTokens, long nowMs) {
            this.tokens = new AtomicInteger(initialTokens);
            this.lastRefillMs = new AtomicLong(nowMs);
            this.lastTouchedMs = new AtomicLong(nowMs);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(GUARDED_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request);
        long now = nowMs();
        Bucket bucket = buckets.computeIfAbsent(
                key, k -> new Bucket(MAX_REQUESTS_PER_HOUR, now));
        bucket.lastTouchedMs.set(now);
        refill(bucket, now);

        if (bucket.tokens.get() <= 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec(bucket, now)));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"rate limit exceeded\"}");
            return;
        }
        bucket.tokens.decrementAndGet();
        chain.doFilter(request, response);
    }

    /**
     * Add tokens to the bucket according to elapsed time since the last
     * refill stamp. Capped at {@link #MAX_REQUESTS_PER_HOUR} so an idle
     * client doesn't accumulate infinite credit.
     */
    void refill(Bucket bucket, long nowMs) {
        long last = bucket.lastRefillMs.get();
        long elapsed = nowMs - last;
        if (elapsed < REFILL_INTERVAL_MS) return;
        long newTokens = elapsed / REFILL_INTERVAL_MS;
        if (newTokens <= 0) return;
        // CAS so concurrent requests on the same bucket can't double-refill.
        // The refill stamp advances by `newTokens * interval` so leftover
        // sub-interval time stays banked for the next round.
        long newStamp = last + newTokens * REFILL_INTERVAL_MS;
        if (!bucket.lastRefillMs.compareAndSet(last, newStamp)) return;
        int updated = bucket.tokens.updateAndGet(
                t -> (int) Math.min(MAX_REQUESTS_PER_HOUR, t + newTokens));
        LOG.debug("Bucket refilled: +{} tokens (cap at {}), now at {}",
                newTokens, MAX_REQUESTS_PER_HOUR, updated);
    }

    /**
     * Seconds until the next token refills. Caps low to 1 — never advertise
     * a 0-second retry that triggers an immediate retry storm.
     */
    long retryAfterSec(Bucket bucket, long nowMs) {
        long elapsed = nowMs - bucket.lastRefillMs.get();
        long remaining = REFILL_INTERVAL_MS - (elapsed % REFILL_INTERVAL_MS);
        long sec = (remaining + 999L) / 1000L; // ceil
        return Math.max(1L, sec);
    }

    /**
     * First X-Forwarded-For entry (the reverse proxy is mandatory in
     * production) falling back to remoteAddr. Cheap to spoof on a
     * non-proxied edge — fine for the institutional model where the
     * proxy is the only access gate.
     */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        return req.getRemoteAddr();
    }

    @Scheduled(fixedRate = 300_000L)
    public void evictIdle() {
        long now = nowMs();
        int before = buckets.size();
        buckets.entrySet().removeIf(e ->
                now - e.getValue().lastTouchedMs.get() > IDLE_BUCKET_TTL_MS);
        int after = buckets.size();
        if (after < before) {
            LOG.debug("Rate-limit bucket eviction: {} -> {}", before, after);
        }
    }

    /** Test seam — overridable clock so unit tests don't have to sleep. */
    long nowMs() {
        return System.currentTimeMillis();
    }

    /** Test seam — inspect bucket state without exposing the map. */
    int currentTokens(String key) {
        Bucket b = buckets.get(key);
        return b == null ? MAX_REQUESTS_PER_HOUR : b.tokens.get();
    }
}
