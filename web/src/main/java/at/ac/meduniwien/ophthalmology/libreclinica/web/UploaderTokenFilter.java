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
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.UploaderTokens;

/**
 * DR-040 phase 2 — the front door of the device endpoints: who is knocking,
 * and for which place.
 *
 * <p>The acquisition-PC uploaders (Export Watcher, Optomed Bridge) and the
 * anonymous upload pages reach the platform without a session. Each uploader
 * now carries a token, issued for one location, in the header
 * {@value #TOKEN_HEADER}; this filter turns it into the ingest source it
 * belongs to and leaves that on the request ({@link UploaderSources}), so the
 * controllers behind it can answer for that location only.
 *
 * <h2>The guarded paths</h2>
 * Every path an anonymous device can reach, under any method (a {@code HEAD}
 * must not be a way round the door): {@code /pages/api/v1/public/upload/**}
 * (the combined page: resolve, commit, the lookups), the two older portals
 * {@code /pages/api/v1/public/oct-upload/**} and
 * {@code /pages/api/v1/public/image-upload/**}, the heartbeat under
 * {@code /pages/api/v1/device/uploader/**} and the Optomed worklist under
 * {@code /pages/api/v1/device/optomed/**}. They are matched with the matcher
 * the authorization rules use ({@link SecurityConfig#pathPattern}), which sees
 * the path as the container routes it: below the context path, percent-escapes
 * decoded, path parameters dropped. A match on the raw request URI would let
 * {@code /public/%75pload/commit} past the door while the dispatcher still
 * routes it to the upload controller.
 *
 * <p>Not guarded: {@code public/bcva-entry} (no file, no device) and
 * everything else; staff upload through {@code /ingest/upload}, which has its
 * own checks. A signed-in session does <em>not</em> get past the door on a
 * guarded path: the controllers behind it check nothing themselves, so a
 * session would see every location, and a same-origin browser upload sends
 * its cookie even when the page means to upload as an uploader.
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li>The header is present. It must be shaped like a token
 *       ({@link UploaderTokens#wellFormed}) and name an active uploader at an
 *       available location ({@link IngestSourceService#lookupActiveByToken}: one
 *       unique-index read, never cached, so a revoked or replaced token stops
 *       working on the next request). Otherwise
 *       <strong>401 {@code {"message":"uploader token rejected"}}</strong>.
 *       Otherwise the {@link IngestSourceService.Source} goes on the request.
 *       Two headers, an empty one, or a source that is not an uploader are
 *       rejected as well. A lookup that fails (the database cannot be reached)
 *       is <strong>503 {@code {"message":"uploader token could not be
 *       checked"}}</strong> with {@code Retry-After}: never a pass, and not
 *       counted as a rejection, so an outage does not leave a working uploader
 *       limited once the database is back.</li>
 *   <li>No header (the Optomed worklist's legacy
 *       {@code X-MUW-Optomed-Token} does not count: a shared secret cannot
 *       say which location is asking). The <em>anonymous door</em> is the
 *       deployment's {@link LocationMode}, never the number of locations: in
 *       single mode the request is served with no source (and filed at the one
 *       location); in multi mode it is
 *       <strong>401 {@code {"message":"uploader token required"}}</strong> from
 *       the first location on. No database read decides it.</li>
 * </ol>
 * A 401 is never a 429: the uploaders keep their files and say so in the tray
 * on the first and file them without a visit on the second.
 *
 * <h2>Bounds</h2>
 * A flood of invented tokens must not become a flood of database reads. Each
 * rejected token is counted against the client's address (the container's
 * {@code getRemoteAddr}, never a forwarded header); past
 * {@value #MAX_FAILURES} in {@code 5} minutes the address is answered
 * <strong>429</strong> with {@code Retry-After} before any lookup is made, until
 * its window passes. Idle counters are dropped. A request without a token costs
 * no read at all.
 *
 * <p><strong>Logging.</strong> A line carries the method, the path and the
 * outcome, at most one per outcome a minute. Never a token, never a hash.
 *
 * <p>Mounted by {@code SecurityConfig.addDeviceFilters} ahead of the rate
 * limiter, which keys its budget by the source.
 */
public class UploaderTokenFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(UploaderTokenFilter.class);

    /** The header an uploader sends on every request to the platform. */
    public static final String TOKEN_HEADER = "X-MUW-Uploader-Token";

    static final String MESSAGE_REQUIRED = "uploader token required";
    static final String MESSAGE_REJECTED = "uploader token rejected";
    static final String MESSAGE_LIMITED = "too many rejected uploader tokens";
    static final String MESSAGE_UNAVAILABLE = "uploader token could not be checked";

    /** Rejected tokens one address may present per window before it is answered 429. */
    static final int MAX_FAILURES = 60;
    static final long FAILURE_WINDOW_MS = 5 * 60_000L;

    /** One log line per outcome this often. */
    static final long LOG_EVERY_MS = 60_000L;

    /** Beyond this many tracked addresses a request also sweeps the idle ones, at most this often. */
    private static final int LAZY_SWEEP_SIZE = 1024;
    private static final long LAZY_SWEEP_EVERY_MS = 30_000L;

    /**
     * The paths an anonymous device can reach, in the form the authorization
     * rules list them ({@code SecurityConfig.PUBLIC_PATHS}), minus the BCVA page.
     */
    static final String[] GUARDED_PATTERNS = {
            "/pages/api/v1/public/upload/**",
            "/pages/api/v1/public/oct-upload/**",
            "/pages/api/v1/public/image-upload/**",
            "/pages/api/v1/device/uploader/**",
            "/pages/api/v1/device/optomed/**",
    };

    private static final RequestMatcher[] GUARDED = new RequestMatcher[GUARDED_PATTERNS.length];

    /** The guarded path patterns (a copy), for the test that every anonymous device entry is among them. */
    public static java.util.List<String> guardedPatterns() {
        return java.util.List.of(GUARDED_PATTERNS);
    }

    static {
        for (int i = 0; i < GUARDED_PATTERNS.length; i++) {
            GUARDED[i] = SecurityConfig.pathPattern(GUARDED_PATTERNS[i]);
        }
    }

    /** What a log line says happened. */
    private enum Outcome {
        REQUIRED("no token, front door closed"),
        REJECTED("token rejected"),
        LIMITED("too many rejected tokens from one address"),
        UNAVAILABLE("token could not be checked (database)");

        final String text;

        Outcome(String text) {
            this.text = text;
        }
    }

    /** A window of rejected tokens from one address. */
    private record Failures(long windowStartMs, int count) {}

    private final Function<String, Optional<IngestSourceService.Source>> lookup;
    private final Supplier<LocationMode> mode;
    private final LongSupplier clock;

    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();
    private volatile long lastSweepMs = Long.MIN_VALUE / 2;

    private final AtomicLong[] loggedAt = {
            new AtomicLong(Long.MIN_VALUE / 2), new AtomicLong(Long.MIN_VALUE / 2), new AtomicLong(Long.MIN_VALUE / 2),
            new AtomicLong(Long.MIN_VALUE / 2)};
    private final AtomicInteger[] suppressed = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
            new AtomicInteger()};

    /** The production filter: the real token lookup, the deployment's location mode and the wall clock. */
    public UploaderTokenFilter(IngestSourceService sources) {
        this(sources::lookupActiveByToken, LocationMode::current, System::currentTimeMillis);
    }

    /** The test seam: every collaborator is a function. */
    UploaderTokenFilter(Function<String, Optional<IngestSourceService.Source>> lookup, Supplier<LocationMode> mode,
                        LongSupplier clock) {
        this.lookup = lookup;
        this.mode = mode;
        this.clock = clock;
    }

    /* ====================================================================== */
    /* The filter                                                              */
    /* ====================================================================== */

    /** No participation in async or error dispatches: the decision is made once, on the first. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @SuppressWarnings("resource") // the servlet container owns and closes the response writer
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (!guarded(request)) {
            chain.doFilter(request, response);
            return;
        }
        UploaderSources.clear(request);
        // A session is no way past the door: these controllers check nothing
        // themselves, and a browser's same-origin upload carries its cookie.

        long now = clock.getAsLong();
        Enumeration<String> values = request.getHeaders(TOKEN_HEADER);
        if (values == null || !values.hasMoreElements()) {
            // No token: the anonymous door.
            if (doorOpen()) {
                chain.doFilter(request, response);
                return;
            }
            note(Outcome.REQUIRED, request, now);
            respond(response, 401, MESSAGE_REQUIRED, 0);
            return;
        }
        String token = values.nextElement();
        boolean ambiguous = values.hasMoreElements();

        // A token is presented: bound what a flood of invented ones can cost before any lookup is made.
        String address = request.getRemoteAddr();
        long waitMs = blockedForMs(address, now);
        if (waitMs > 0) {
            note(Outcome.LIMITED, request, now);
            respond(response, 429, MESSAGE_LIMITED, Math.max(1L, (waitMs + 999L) / 1000L));
            return;
        }

        IngestSourceService.Source source;
        try {
            source = ambiguous || !UploaderTokens.wellFormed(token) ? null : find(token);
        } catch (LookupFailed outage) {
            // Not the uploader's fault: no rejection is counted. The scripts treat it like any other 5xx.
            note(Outcome.UNAVAILABLE, request, now);
            respond(response, 503, MESSAGE_UNAVAILABLE, 30);
            return;
        }
        if (source == null) {
            recordFailure(address, now);
            note(Outcome.REJECTED, request, now);
            respond(response, 401, MESSAGE_REJECTED, 0);
            return;
        }
        UploaderSources.bind(request, source);
        if (LOG.isDebugEnabled()) {
            LOG.debug("uploader token accepted for source {} on {}", source.id(), request.getRequestURI());
        }
        chain.doFilter(request, response);
    }

    /**
     * The active uploader a token belongs to, or null. A lookup that blows up is
     * never a pass: it throws {@link LookupFailed}, which answers 503.
     */
    private IngestSourceService.Source find(String token) {
        try {
            return lookup.apply(token)
                    .filter(s -> s.active() && IngestSourceService.KIND_UPLOADER.equals(s.kind())
                            && s.locationId() != null)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("uploader token lookup failed ({})", e.getClass().getSimpleName());
            throw new LookupFailed();
        }
    }

    /** The token could not be checked at all (the database is unreachable). */
    private static final class LookupFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LookupFailed() {
            super(null, null, false, false);
        }
    }

    /* ====================================================================== */
    /* Which requests, and the front door                                      */
    /* ====================================================================== */

    /** True for a path an anonymous device can reach. A matcher that cannot decide is not a way past the door. */
    static boolean guarded(HttpServletRequest request) {
        for (RequestMatcher m : GUARDED) {
            try {
                if (m.matches(request)) return true;
            } catch (RuntimeException undecidable) {
                return true;
            }
        }
        return false;
    }

    /** An untokened request is served exactly in single-location mode. */
    private boolean doorOpen() {
        return mode.get() == LocationMode.SINGLE;
    }

    /* ====================================================================== */
    /* Rejected tokens per address                                             */
    /* ====================================================================== */

    /** Milliseconds the address must wait, or 0 when it may present a token. */
    private long blockedForMs(String address, long now) {
        Failures f = failures.get(address);
        if (f == null || now - f.windowStartMs() >= FAILURE_WINDOW_MS || f.count() < MAX_FAILURES) return 0L;
        return f.windowStartMs() + FAILURE_WINDOW_MS - now;
    }

    private void recordFailure(String address, long now) {
        failures.merge(address, new Failures(now, 1), (old, fresh) ->
                now - old.windowStartMs() >= FAILURE_WINDOW_MS ? fresh : new Failures(old.windowStartMs(), old.count() + 1));
        if (failures.size() > LAZY_SWEEP_SIZE && now - lastSweepMs >= LAZY_SWEEP_EVERY_MS) {
            lastSweepMs = now;
            sweep(now);
        }
    }

    /** Drops the counters whose window has passed; also run on a schedule, so idle addresses do not pile up. */
    @Scheduled(fixedRate = 300_000L)
    public void evictIdle() {
        sweep(clock.getAsLong());
    }

    private void sweep(long now) {
        failures.entrySet().removeIf(e -> now - e.getValue().windowStartMs() >= FAILURE_WINDOW_MS);
    }

    /** Test seam: how many addresses are being counted. */
    int trackedAddresses() {
        return failures.size();
    }

    /* ====================================================================== */
    /* Answering and logging                                                   */
    /* ====================================================================== */

    @SuppressWarnings("resource") // the servlet container owns and closes the response writer
    private static void respond(HttpServletResponse response, int status, String message, long retryAfterSec)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (retryAfterSec > 0) response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    /**
     * One line per outcome and minute: the method, the path and what happened.
     * A misconfigured uploader retries all day and an attacker as fast as it can;
     * neither fills the log. The remote address, the header and the token stay out.
     */
    private void note(Outcome outcome, HttpServletRequest request, long now) {
        int i = outcome.ordinal();
        long last = loggedAt[i].get();
        if (now - last >= LOG_EVERY_MS && loggedAt[i].compareAndSet(last, now)) {
            int skipped = suppressed[i].getAndSet(0);
            LOG.warn("device endpoint {} {}: {}{}", request.getMethod(), request.getRequestURI(), outcome.text,
                    skipped > 0 ? " (" + skipped + " similar in the last minute)" : "");
        } else {
            suppressed[i].incrementAndGet();
        }
    }
}
