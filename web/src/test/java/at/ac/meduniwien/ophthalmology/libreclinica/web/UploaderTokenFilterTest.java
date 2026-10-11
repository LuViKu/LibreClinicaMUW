/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Source;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.UploaderTokens;

/**
 * DR-040 phase 2 — the front door of the device endpoints, with every
 * collaborator replaced by a function: which requests it looks at, what it
 * answers to a token and to none, what a flood of invented tokens costs, and
 * what it never writes down.
 *
 * <p>The database side (the real {@code findActiveByToken}, the real location
 * count, the controllers behind it) is {@code UploaderTokenDatabaseIT}.
 */
class UploaderTokenFilterTest {

    private static final String CTX = "/LibreClinica";
    private static final String RESOLVE = "/pages/api/v1/public/upload/resolve";
    private static final String HEARTBEAT = "/pages/api/v1/device/uploader/heartbeat";
    private static final String WORKLIST = "/pages/api/v1/device/optomed/worklist.txt";

    private final Map<String, Source> sources = new HashMap<>();
    private final List<String> lookedUp = new ArrayList<>();
    /** The deployment's location mode as the filter sees it. */
    private final AtomicReference<LocationMode> mode = new AtomicReference<>(LocationMode.SINGLE);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    /** While true, the lookup fails as an unreachable database does. */
    private boolean outage;

    private UploaderTokenFilter filter;

    @BeforeEach
    void build() {
        filter = newFilter();
    }

    private UploaderTokenFilter newFilter() {
        return new UploaderTokenFilter(
                token -> {
                    lookedUp.add(token);
                    if (outage) throw new IllegalStateException("database unreachable");
                    return Optional.ofNullable(sources.get(token));
                },
                mode::get,
                clock::get);
    }

    private Source uploader(long id, int location) {
        return new Source(id, IngestSourceService.KIND_UPLOADER, null, "PC " + id, location, "abcd1234",
                IngestSourceService.STATUS_ACTIVE);
    }

    /** A token the lookup knows, for the source. */
    private String issue(Source source) {
        String token = UploaderTokens.generate();
        sources.put(token, source);
        return token;
    }

    /* ---------------------------------------------------------------- */
    /* the request and the answer                                        */
    /* ---------------------------------------------------------------- */

    private static final class Outcome {
        final MockHttpServletRequest request;
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain chain = new MockFilterChain();

        Outcome(MockHttpServletRequest request) {
            this.request = request;
        }

        /** True when the request went on to the controllers. */
        boolean passed() {
            return chain.getRequest() != null;
        }

        Source source() {
            return UploaderSources.of(request);
        }

        String body() throws Exception {
            return response.getContentAsString();
        }
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, CTX + path);
        r.setContextPath(CTX);
        r.setServletPath("/pages");
        r.setPathInfo(path.substring("/pages".length()));
        r.setRemoteAddr("10.1.1.1");
        if (token != null) r.addHeader(UploaderTokenFilter.TOKEN_HEADER, token);
        return r;
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        Outcome o = new Outcome(request);
        filter.doFilter(request, o.response, o.chain);
        return o;
    }

    private Outcome post(String path, String token) throws Exception {
        return run(request("POST", path, token));
    }

    private Outcome post(String path) throws Exception {
        return post(path, null);
    }

    private void assertAnswered(Outcome o, int status, String message) throws Exception {
        assertFalse(o.passed(), "the request must not reach the controllers");
        assertEquals(status, o.response.getStatus());
        assertEquals("{\"message\":\"" + message + "\"}", o.body());
        assertTrue(String.valueOf(o.response.getContentType()).startsWith("application/json"),
                "JSON: " + o.response.getContentType());
        assertEquals("no-store", o.response.getHeader("Cache-Control"));
        assertNull(o.source(), "an answered request carries no source");
    }

    /* ---------------------------------------------------------------- */
    /* rule (a): a token                                                 */
    /* ---------------------------------------------------------------- */

    @Test
    void aValidTokenBindsItsSourceToTheRequestAndPassesIt() throws Exception {
        Source mine = uploader(7, 3);
        String token = issue(mine);
        mode.set(LocationMode.MULTI); // the door is shut, the token opens it for its own place

        Outcome o = post(RESOLVE, token);

        assertTrue(o.passed());
        assertEquals(200, o.response.getStatus());
        assertSame(mine, o.source());
        assertEquals(List.of(token), lookedUp);
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        assertAnswered(post(RESOLVE, UploaderTokens.generate()), 401, "uploader token rejected");
    }

    @Test
    void aTokenThatIsNotShapedLikeOneIsRejectedWithoutALookup() throws Exception {
        for (String bad : new String[] {"nonsense", "mwu_short", "mwu_" + "x".repeat(44), "MWU_" + "x".repeat(43),
                "mwu_" + "!".repeat(43), "  ", ""}) {
            assertAnswered(post(RESOLVE, bad), 401, "uploader token rejected");
        }
        assertTrue(lookedUp.isEmpty(), "no read for something that cannot be a token: " + lookedUp);
    }

    @Test
    void aRevokedTokenAndAnUnassignedOneAreRejected() throws Exception {
        String revoked = issue(new Source(1, IngestSourceService.KIND_UPLOADER, null, "old", 3, "abcd1234",
                IngestSourceService.STATUS_REVOKED));
        String unassigned = issue(new Source(2, IngestSourceService.KIND_UPLOADER, null, "new", null, "abcd1234",
                IngestSourceService.STATUS_ACTIVE));
        assertAnswered(post(RESOLVE, revoked), 401, "uploader token rejected");
        assertAnswered(post(RESOLVE, unassigned), 401, "uploader token rejected");
    }

    @Test
    void aSourceThatIsNotAnUploaderIsRejected() throws Exception {
        String ae = issue(new Source(3, IngestSourceService.KIND_DICOM_AE, "CAMERA", "camera", 3, null,
                IngestSourceService.STATUS_ACTIVE));
        assertAnswered(post(RESOLVE, ae), 401, "uploader token rejected");
    }

    @Test
    void twoTokenHeadersAreRejected() throws Exception {
        String good = issue(uploader(7, 3));
        MockHttpServletRequest r = request("POST", RESOLVE, good);
        r.addHeader(UploaderTokenFilter.TOKEN_HEADER, good);
        assertAnswered(run(r), 401, "uploader token rejected");
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    void aFailingLookupIsNeverAPass() throws Exception {
        UploaderTokenFilter broken = new UploaderTokenFilter(
                t -> { throw new IllegalStateException("database down"); }, mode::get, clock::get);
        Outcome o = new Outcome(request("POST", RESOLVE, UploaderTokens.generate()));
        broken.doFilter(o.request, o.response, o.chain);
        assertAnswered(o, 503, "uploader token could not be checked");
    }

    @Test
    void aTokenWinsOverTheDoorEvenWhereTheDoorIsOpen() throws Exception {
        // One location, door allowed: a bad token is still a bad token, not an anonymous request.
        assertAnswered(post(RESOLVE, UploaderTokens.generate()), 401, "uploader token rejected");
    }

    @Test
    void aStaleSourceOnTheRequestNeverSurvivesTheFilter() throws Exception {
        MockHttpServletRequest r = request("POST", RESOLVE, null);
        r.setAttribute(UploaderSources.ATTRIBUTE, uploader(99, 3));
        Outcome o = run(r); // single mode: served, with no source
        assertTrue(o.passed());
        assertNull(o.source());

        mode.set(LocationMode.MULTI);
        MockHttpServletRequest r2 = request("POST", RESOLVE, UploaderTokens.generate());
        r2.setAttribute(UploaderSources.ATTRIBUTE, uploader(99, 3));
        assertAnswered(run(r2), 401, "uploader token rejected");
    }

    /* ---------------------------------------------------------------- */
    /* rule (b): no token, the anonymous door                            */
    /* ---------------------------------------------------------------- */

    @Test
    void inSingleLocationModeAnUntokenedRequestIsServedAsBefore() throws Exception {
        Outcome o = post(RESOLVE);
        assertTrue(o.passed());
        assertEquals(200, o.response.getStatus());
        assertNull(o.source());
        assertTrue(lookedUp.isEmpty(), "no token, no lookup");
    }

    @Test
    void inMultiLocationModeAnUntokenedRequestIsToldToSendAToken() throws Exception {
        // From the first location on: the mode decides, not how many locations exist.
        mode.set(LocationMode.MULTI);
        assertAnswered(post(RESOLVE), 401, "uploader token required");
        assertAnswered(post(HEARTBEAT), 401, "uploader token required");
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    void theModeIsReadOnEveryRequest() throws Exception {
        assertTrue(post(RESOLVE).passed());
        mode.set(LocationMode.MULTI);
        assertAnswered(post(RESOLVE), 401, "uploader token required");
        mode.set(LocationMode.SINGLE);
        assertTrue(post(RESOLVE).passed());
    }

    @Test
    void aTokenOpensAShutDoorForItsOwnPlaceOnly() throws Exception {
        mode.set(LocationMode.MULTI);
        Source mine = uploader(5, 2);
        Outcome o = post(RESOLVE, issue(mine));
        assertTrue(o.passed());
        assertSame(mine, o.source());
        assertAnswered(post(RESOLVE), 401, "uploader token required");
    }

    @Test
    void inSingleLocationModeAValidTokenStillBindsItsSource() throws Exception {
        // An uploader prepared for a later switch already files under its own name.
        Source mine = uploader(5, 1);
        Outcome o = post(RESOLVE, issue(mine));
        assertTrue(o.passed());
        assertSame(mine, o.source());
    }

    @Test
    void theLegacyOptomedTokenIsNotAnUploaderToken() throws Exception {
        // It cannot say which location is asking: in single mode the worklist goes on to its own
        // shared-token gate, in multi mode the door is shut.
        MockHttpServletRequest legacy = request("GET", WORKLIST, null);
        legacy.addHeader("X-MUW-Optomed-Token", "the-shared-secret");
        Outcome one = run(legacy);
        assertTrue(one.passed());
        assertNull(one.source());
        assertTrue(lookedUp.isEmpty(), "the legacy token is never looked up as an uploader token");

        mode.set(LocationMode.MULTI);
        MockHttpServletRequest again = request("GET", WORKLIST, null);
        again.addHeader("X-MUW-Optomed-Token", "the-shared-secret");
        assertAnswered(run(again), 401, "uploader token required");
    }

    @Test
    void theWorklistTakesBothTokensAndTheUploaderOneDecides() throws Exception {
        Source mine = uploader(5, 2);
        MockHttpServletRequest both = request("GET", WORKLIST, issue(mine));
        both.addHeader("X-MUW-Optomed-Token", "the-shared-secret");
        mode.set(LocationMode.MULTI);
        Outcome o = run(both);
        assertTrue(o.passed());
        assertSame(mine, o.source());
    }

    /* ---------------------------------------------------------------- */
    /* which requests                                                    */
    /* ---------------------------------------------------------------- */

    @Test
    void everyPathAnAnonymousDeviceCanReachIsGuardedUnderTheDeployedContextPath() throws Exception {
        mode.set(LocationMode.MULTI);
        for (String path : new String[] {
                "/pages/api/v1/public/upload/resolve", "/pages/api/v1/public/upload/commit",
                "/pages/api/v1/public/upload/patients/search", "/pages/api/v1/public/upload/patients/12/events",
                "/pages/api/v1/public/upload/visits", "/pages/api/v1/public/upload/preflight",
                "/pages/api/v1/public/upload/items/5", "/pages/api/v1/public/upload/jobs/5",
                "/pages/api/v1/public/oct-upload/resolve", "/pages/api/v1/public/oct-upload/commit",
                "/pages/api/v1/public/image-upload/resolve", "/pages/api/v1/public/image-upload/commit",
                "/pages/api/v1/device/uploader/heartbeat", "/pages/api/v1/device/optomed/worklist.txt"}) {
            assertAnswered(post(path), 401, "uploader token required");
        }
    }

    @Test
    void theMethodDoesNotMatterNoHeadOrGetIsAWayRoundTheDoor() throws Exception {
        mode.set(LocationMode.MULTI);
        for (String method : new String[] {"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"}) {
            assertAnswered(run(request(method, WORKLIST, null)), 401, "uploader token required");
            assertAnswered(run(request(method, RESOLVE, null)), 401, "uploader token required");
        }
    }

    @Test
    void thePathIsMatchedAsTheContainerRoutesItNotAsItIsWritten() throws Exception {
        mode.set(LocationMode.MULTI);
        // An escaped letter, a path parameter: the dispatcher still routes these to the upload controller.
        MockHttpServletRequest escaped = new MockHttpServletRequest("POST", CTX + "/pages/api/v1/public/%75pload/commit");
        escaped.setContextPath(CTX);
        escaped.setServletPath("/pages");
        escaped.setPathInfo("/api/v1/public/upload/commit");
        assertAnswered(run(escaped), 401, "uploader token required");

        MockHttpServletRequest param = new MockHttpServletRequest("POST",
                CTX + "/pages/api/v1/public/upload;jsessionid=ABC/commit");
        param.setContextPath(CTX);
        param.setServletPath("/pages");
        param.setPathInfo("/api/v1/public/upload/commit");
        assertAnswered(run(param), 401, "uploader token required");

        // Even when the container hands over nothing but the raw URI.
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", CTX + "/pages/api/v1/public/%75pload/commit");
        raw.setContextPath(CTX);
        assertAnswered(run(raw), 401, "uploader token required");

        MockHttpServletRequest noContext = new MockHttpServletRequest("POST", RESOLVE);
        assertAnswered(run(noContext), 401, "uploader token required");
    }

    @Test
    void whatIsNotADeviceEndpointIsNotTouched() throws Exception {
        mode.set(LocationMode.MULTI);
        for (String path : new String[] {
                "/pages/api/v1/public/bcva-entry/S_X/visits", "/pages/api/v1/me", "/pages/api/v1/uploaders",
                "/pages/api/v1/ingest/upload/commit", "/pages/api/v1/internal/dicom-worklist",
                "/pages/api/v1/internal/dicom-ingest", "/pages/auth/api/v1/studies", "/pages/login/login",
                "/pages/api/v1/public/upload-other/x", "/pages/api/v1/device/other/x"}) {
            Outcome o = post(path, UploaderTokens.generate());
            assertTrue(o.passed(), path + " goes on untouched");
            assertEquals(200, o.response.getStatus(), path);
            assertNull(o.source(), path);
        }
        assertTrue(lookedUp.isEmpty(), "no lookups for paths the filter does not guard");
    }

    @Test
    void theLegacyServletsAreNotTouched() throws Exception {
        mode.set(LocationMode.MULTI);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", CTX + "/ListStudySubjects");
        r.setContextPath(CTX);
        r.setServletPath("/ListStudySubjects");
        assertTrue(run(r).passed());
    }

    /* ---------------------------------------------------------------- */
    /* a signed-in user                                                  */
    /* ---------------------------------------------------------------- */

    private static MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("jdoe", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER"))));
        return session;
    }

    @Test
    void aSignedInSessionIsNoWayPastTheDoor() throws Exception {
        // Multi mode: a session without a token is refused like anyone else ...
        mode.set(LocationMode.MULTI);
        MockHttpServletRequest noHeader = request("GET", WORKLIST, null);
        noHeader.setSession(signedInSession());
        assertAnswered(run(noHeader), 401, "uploader token required");

        // ... and a session with a token is bound to that token's location, as a browser's upload is.
        String good = issue(uploader(7, 3));
        MockHttpServletRequest withToken = request("POST", RESOLVE, good);
        withToken.setSession(signedInSession());
        Outcome o = run(withToken);
        assertTrue(o.passed());
        assertEquals(7L, o.source().id());
    }

    @Test
    void anAnonymousOrUnauthenticatedSessionIsNotASignedInUser() throws Exception {
        mode.set(LocationMode.MULTI);
        MockHttpSession anonymous = new MockHttpSession();
        anonymous.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        MockHttpServletRequest r = request("POST", RESOLVE, null);
        r.setSession(anonymous);
        assertAnswered(run(r), 401, "uploader token required");

        MockHttpSession pending = new MockHttpSession();
        pending.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("jdoe", "n/a")));
        MockHttpServletRequest p = request("POST", RESOLVE, null);
        p.setSession(pending);
        assertAnswered(run(p), 401, "uploader token required");

        MockHttpServletRequest empty = request("POST", RESOLVE, null);
        empty.setSession(new MockHttpSession());
        assertAnswered(run(empty), 401, "uploader token required");
    }

    /* ---------------------------------------------------------------- */
    /* the bound on rejected tokens                                      */
    /* ---------------------------------------------------------------- */

    private Outcome bad(String address) throws Exception {
        MockHttpServletRequest r = request("POST", RESOLVE, UploaderTokens.generate());
        r.setRemoteAddr(address);
        return run(r);
    }

    @Test
    void sixtyRejectedTokensAreAnswered401AndTheNextOneIs429WithoutALookup() throws Exception {
        for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES; i++) {
            assertAnswered(bad("10.9.9.9"), 401, "uploader token rejected");
        }
        assertEquals(UploaderTokenFilter.MAX_FAILURES, lookedUp.size());

        Outcome limited = bad("10.9.9.9");
        assertAnswered(limited, 429, "too many rejected uploader tokens");
        String retryAfter = limited.response.getHeader("Retry-After");
        assertNotNull(retryAfter);
        assertEquals(300, Integer.parseInt(retryAfter), "the whole window is left");
        assertEquals(UploaderTokenFilter.MAX_FAILURES, lookedUp.size(), "the 429 costs no read");

        // Not a single token is looked at while the address is limited, a good one included.
        String good = issue(uploader(7, 3));
        MockHttpServletRequest withGood = request("POST", RESOLVE, good);
        withGood.setRemoteAddr("10.9.9.9");
        assertAnswered(run(withGood), 429, "too many rejected uploader tokens");
        assertEquals(UploaderTokenFilter.MAX_FAILURES, lookedUp.size());
    }

    @Test
    void aLookupThatFailsIsAnswered503AndIsNotCountedAgainstTheAddress() throws Exception {
        String good = issue(uploader(7, 3));
        outage = true;
        for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES * 2; i++) {
            MockHttpServletRequest r = request("POST", RESOLVE, good);
            r.setRemoteAddr("10.9.9.9");
            Outcome o = run(r);
            assertAnswered(o, 503, "uploader token could not be checked");
            assertNotNull(o.response.getHeader("Retry-After"));
        }
        // The database is back: the same uploader from the same address is served at once, not 429.
        outage = false;
        MockHttpServletRequest after = request("POST", RESOLVE, good);
        after.setRemoteAddr("10.9.9.9");
        Outcome o = run(after);
        assertTrue(o.passed());
        assertEquals(7L, o.source().id());
    }

    @Test
    void theBoundIsPerAddressAndOnlyForRequestsThatPresentAToken() throws Exception {
        for (int i = 0; i <= UploaderTokenFilter.MAX_FAILURES; i++) bad("10.9.9.9");
        assertAnswered(bad("10.9.9.9"), 429, "too many rejected uploader tokens");

        // Another address is not affected.
        assertAnswered(bad("10.9.9.10"), 401, "uploader token rejected");
        // A request from the limited address that presents no token follows the door, as ever.
        MockHttpServletRequest anonymous = request("POST", RESOLVE, null);
        anonymous.setRemoteAddr("10.9.9.9");
        assertTrue(run(anonymous).passed());
    }

    @Test
    void theBoundIsTheContainersAddressNeverAForwardedHeader() throws Exception {
        for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES; i++) {
            MockHttpServletRequest r = request("POST", RESOLVE, UploaderTokens.generate());
            r.setRemoteAddr("10.9.9.9");
            r.addHeader("X-Forwarded-For", "203.0.113." + i);
            run(r);
        }
        MockHttpServletRequest again = request("POST", RESOLVE, UploaderTokens.generate());
        again.setRemoteAddr("10.9.9.9");
        again.addHeader("X-Forwarded-For", "198.51.100.1");
        assertAnswered(run(again), 429, "too many rejected uploader tokens");
    }

    @Test
    void aWindowThatHasPassedStartsOverAndIdleCountersAreDropped() throws Exception {
        for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES; i++) bad("10.9.9.9");
        bad("10.9.9.8");
        assertAnswered(bad("10.9.9.9"), 429, "too many rejected uploader tokens");
        assertEquals(2, filter.trackedAddresses());

        // The limit does not extend itself: hammering the 429 leaves the window where it was.
        clock.addAndGet(UploaderTokenFilter.FAILURE_WINDOW_MS - 1_000L);
        Outcome nearEnd = bad("10.9.9.9");
        assertAnswered(nearEnd, 429, "too many rejected uploader tokens");
        assertEquals(1, Integer.parseInt(nearEnd.response.getHeader("Retry-After")));

        clock.addAndGet(1_000L);
        filter.evictIdle();
        assertEquals(0, filter.trackedAddresses(), "both windows have passed: nothing is kept");
        assertAnswered(bad("10.9.9.9"), 401, "uploader token rejected");
        assertEquals(1, filter.trackedAddresses());
    }

    @Test
    void aLimitedAddressIsFreeAgainWhenItsWindowPassesEvenWithoutAnEviction() throws Exception {
        for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES; i++) bad("10.9.9.9");
        assertAnswered(bad("10.9.9.9"), 429, "too many rejected uploader tokens");
        clock.addAndGet(UploaderTokenFilter.FAILURE_WINDOW_MS);
        assertAnswered(bad("10.9.9.9"), 401, "uploader token rejected");
        // and the counting begins afresh
        for (int i = 1; i < UploaderTokenFilter.MAX_FAILURES; i++) bad("10.9.9.9");
        assertAnswered(bad("10.9.9.9"), 429, "too many rejected uploader tokens");
    }

    @Test
    void ordinaryAcceptedTokensNeverCountAgainstTheBound() throws Exception {
        String good = issue(uploader(7, 3));
        for (int i = 0; i < 5 * UploaderTokenFilter.MAX_FAILURES; i++) {
            assertTrue(post(RESOLVE, good).passed(), "request " + i);
        }
        assertEquals(0, filter.trackedAddresses());
    }

    /* ---------------------------------------------------------------- */
    /* what is written down                                              */
    /* ---------------------------------------------------------------- */

    /** Everything the filter logs while {@code body} runs, at every level. */
    private List<String> logged(ThrowingRunnable body) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(UploaderTokenFilter.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.ALL);
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            lines.add(e.getLevel() + " " + e.getFormattedMessage());
            if (e.getThrowableProxy() != null) lines.add(String.valueOf(e.getThrowableProxy().getMessage()));
        }
        return lines;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void neitherATokenNorItsHashIsEverLoggedAndALineSaysThePathAndWhatHappened() throws Exception {
        String known = issue(uploader(7, 3));
        String unknown = UploaderTokens.generate();
        String malformed = "mwu_" + "q".repeat(20);
        List<String> lines = logged(() -> {
            post(RESOLVE, known);                 // accepted
            post(RESOLVE, unknown);               // rejected
            post(RESOLVE, malformed);             // rejected
            mode.set(LocationMode.MULTI);
            post("/pages/api/v1/public/oct-upload/commit");   // required
            for (int i = 0; i < UploaderTokenFilter.MAX_FAILURES + 1; i++) bad("10.7.7.7"); // limited
        });

        assertFalse(lines.isEmpty(), "the refusals are written down");
        for (String token : new String[] {known, unknown, malformed}) {
            String hash = UploaderTokens.hash(token);
            for (String line : lines) {
                assertFalse(line.contains(token), "no token in the log: " + line);
                assertFalse(line.contains(token.substring(4)), "no part of a token in the log: " + line);
                assertFalse(line.contains(hash), "no hash in the log: " + line);
                assertFalse(line.contains(hash.substring(0, 12)), "no part of a hash in the log: " + line);
            }
        }
        for (String line : lines) {
            assertFalse(line.contains("mwu_"), "nothing shaped like a token: " + line);
            assertFalse(line.contains("10.7.7.7"), "no client address: " + line);
            assertFalse(line.contains(UploaderTokenFilter.TOKEN_HEADER), "no header: " + line);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("POST") && l.contains(RESOLVE) && l.contains("token rejected")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("/pages/api/v1/public/oct-upload/commit")
                && l.contains("no token, front door closed")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("too many rejected tokens")), lines.toString());
    }

    @Test
    void aFloodOfRefusalsWritesOneLinePerOutcomeAndMinute() throws Exception {
        mode.set(LocationMode.MULTI);
        List<String> lines = logged(() -> {
            for (int i = 0; i < 40; i++) post(RESOLVE);
            for (int i = 0; i < 40; i++) bad("10.5.5." + i); // different addresses: all plain rejections
        });
        assertEquals(2, lines.size(), lines.toString());

        clock.addAndGet(UploaderTokenFilter.LOG_EVERY_MS);
        List<String> next = logged(() -> post(RESOLVE));
        assertEquals(1, next.size());
        assertTrue(next.get(0).contains("39 similar"), "what was held back is counted: " + next);
    }

    /* ---------------------------------------------------------------- */
    /* wiring                                                            */
    /* ---------------------------------------------------------------- */

    @Test
    void theFilterIsNotAlsoRegisteredBySpringBootAsAServletFilter() {
        var config = new at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig();
        var registration = config.uploaderTokenFilterAutoRegOptOut(filter);
        assertFalse(registration.isEnabled(), "mounted inside the security chain only");
        assertSame(filter, registration.getFilter());
        assertTrue(registration.isAsyncSupported());
    }
}
