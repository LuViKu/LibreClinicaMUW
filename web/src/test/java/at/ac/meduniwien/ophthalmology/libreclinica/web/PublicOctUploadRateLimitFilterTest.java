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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Source;

/**
 * Wave 1B unit coverage for the hand-rolled token-bucket on the public
 * OCT-upload portal.
 *
 * <p>The test seam is {@link PublicOctUploadRateLimitFilter#nowMs()} so
 * we don't have to sleep through real wall-clock time to exercise the
 * refill window.
 */
class PublicOctUploadRateLimitFilterTest {

    @Test
    void allowsRequestsUnderTheLimit() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            MockHttpServletResponse resp = invoke(filter, "1.2.3.4");
            assertEquals(200, resp.getStatus(), "request " + i + " should pass");
        }
    }

    @Test
    void rejects31stRequestWithRetryAfter() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            invoke(filter, "1.2.3.4");
        }
        MockHttpServletResponse resp = invoke(filter, "1.2.3.4");
        assertEquals(429, resp.getStatus(), "31st request should be rate-limited");
        String retryAfter = resp.getHeader("Retry-After");
        assertNotNull(retryAfter, "Retry-After must be set on 429");
        assertTrue(Integer.parseInt(retryAfter) >= 1, "Retry-After must be >= 1");
        assertTrue(resp.getContentAsString().contains("rate limit exceeded"));
    }

    @Test
    void refillsAfterRefillInterval() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            invoke(filter, "5.6.7.8");
        }
        assertEquals(429, invoke(filter, "5.6.7.8").getStatus());

        // Advance the clock past the refill interval; one token should be back.
        filter.nowMs = PublicOctUploadRateLimitFilter.REFILL_INTERVAL_MS + 1L;
        assertEquals(200, invoke(filter, "5.6.7.8").getStatus(),
                "after refill one request should succeed");
        // The next one (no time advance) is rate-limited again.
        assertEquals(429, invoke(filter, "5.6.7.8").getStatus());
    }

    @Test
    void unguardedPathBypassesBucket() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/something/else");
        req.setRequestURI("/pages/something/else");
        req.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertEquals(200, resp.getStatus());
        // Bucket untouched — full credit remains.
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR,
                filter.currentTokens("9.9.9.9"));
    }

    @Test
    void distinctIpsHaveSeparateBuckets() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            invoke(filter, "10.0.0.1");
        }
        assertEquals(429, invoke(filter, "10.0.0.1").getStatus());
        // A different IP starts fresh.
        assertEquals(200, invoke(filter, "10.0.0.2").getStatus());
    }

    @Test
    void ignoresSpoofableXForwardedFor() throws Exception {
        // The client rotates X-Forwarded-For on every request to dodge its
        // bucket; the bucket is keyed on remoteAddr, so the limit still bites.
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            MockHttpServletRequest req = guardedRequest();
            req.addHeader("X-Forwarded-For", "203.0.113." + i);
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            assertEquals(200, resp.getStatus());
        }
        MockHttpServletRequest spoofed = guardedRequest();
        spoofed.addHeader("X-Forwarded-For", "198.51.100.99");
        spoofed.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(spoofed, resp, new MockFilterChain());
        assertEquals(429, resp.getStatus());
        assertEquals("10.0.0.1", PublicOctUploadRateLimitFilter.clientIp(spoofed));
    }

    @Test
    void evictIdle_dropsBucketsOlderThanTtl() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        invoke(filter, "8.8.8.8");
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR - 1,
                filter.currentTokens("8.8.8.8"));

        // Advance the clock well past the idle TTL and trigger eviction.
        filter.nowMs = PublicOctUploadRateLimitFilter.IDLE_BUCKET_TTL_MS + 1L;
        filter.evictIdle();

        // Bucket gone → currentTokens returns the default MAX value.
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR,
                filter.currentTokens("8.8.8.8"));
    }

    @Test
    void siblingPublicPortalsAreThrottledInSeparateBuckets() throws Exception {
        // 2026-09-17 — the DR-025 image-upload and BCVA portals share the
        // policy but not the bucket: exhausting one leaves the others fresh.
        ClockableFilter filter = new ClockableFilter(0L);
        String image = "/pages/api/v1/public/image-upload/commit";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invoke(filter, "10.0.0.5", image).getStatus());
        }
        assertEquals(429, invoke(filter, "10.0.0.5", image).getStatus());
        assertEquals(200, invoke(filter, "10.0.0.5", "/pages/api/v1/public/bcva-entry/S_X/visits").getStatus());
        assertEquals(200, invoke(filter, "10.0.0.5").getStatus()); // OCT portal bucket untouched
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR - 1,
                filter.currentTokens("10.0.0.5"));
        assertEquals(0, filter.currentTokens("10.0.0.5", "/pages/api/v1/public/image-upload/"));
    }

    @Test
    void uploadPageCommitsDoNotSpendTheLookupBudget() throws Exception {
        // DR-029 — a Clarus visit is half a dozen files; the lookups are what
        // an enumeration attack would use, so only they keep the 30/h budget.
        ClockableFilter filter = new ClockableFilter(0L);
        String search = "/pages/api/v1/public/upload/patients/search";
        String commit = "/pages/api/v1/public/upload/commit";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invoke(filter, "10.0.0.9", search).getStatus());
        }
        assertEquals(429, invoke(filter, "10.0.0.9", search).getStatus());
        // The lookups are spent; a commit still goes through, on its own budget.
        assertEquals(200, invoke(filter, "10.0.0.9", commit).getStatus());
        assertEquals(PublicOctUploadRateLimitFilter.MAX_COMMITS_PER_HOUR - 1,
                filter.currentCommitTokens("10.0.0.9"));
        assertEquals(0, filter.currentTokens("10.0.0.9", PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
    }

    @Test
    void uploadPageCommitsHaveALargerBudgetOfTheirOwn() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        String commit = "/pages/api/v1/public/upload/commit";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_COMMITS_PER_HOUR; i++) {
            assertEquals(200, invoke(filter, "10.0.0.10", commit).getStatus(), "commit " + i);
        }
        assertEquals(429, invoke(filter, "10.0.0.10", commit).getStatus());
        // Spent commits leave the lookups untouched...
        assertEquals(200, invoke(filter, "10.0.0.10", "/pages/api/v1/public/upload/visits").getStatus());
        // ...and refill at their own, faster rate.
        filter.nowMs = PublicOctUploadRateLimitFilter.COMMIT_REFILL_INTERVAL_MS + 1L;
        assertEquals(200, invoke(filter, "10.0.0.10", commit).getStatus());
        assertEquals(429, invoke(filter, "10.0.0.10", commit).getStatus());
    }

    @Test
    void theOlderPortalsKeepCountingCommitsAgainstTheOneBudget() throws Exception {
        // The split is for the combined page only; the pages it replaces are
        // left exactly as they were for the release they stay alive.
        ClockableFilter filter = new ClockableFilter(0L);
        String image = "/pages/api/v1/public/image-upload/commit";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invoke(filter, "10.0.0.11", image).getStatus());
        }
        assertEquals(429, invoke(filter, "10.0.0.11", image).getStatus());
    }

    @Test
    void heartbeatsHaveTheirOwnBudgetAndNeverSpendTheLookups() throws Exception {
        // DR-033 — two uploaders behind the Clarus PC's one address report
        // every two minutes; a heartbeat must not cost the watcher a /resolve.
        ClockableFilter filter = new ClockableFilter(0L);
        String heartbeat = "/pages/api/v1/device/uploader/heartbeat";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_HEARTBEATS_PER_HOUR; i++) {
            assertEquals(200, invoke(filter, "10.0.0.12", heartbeat).getStatus(), "heartbeat " + i);
        }
        assertEquals(429, invoke(filter, "10.0.0.12", heartbeat).getStatus());
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR,
                filter.currentTokens("10.0.0.12", PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        assertEquals(200, invoke(filter, "10.0.0.12", "/pages/api/v1/public/upload/resolve").getStatus());
        filter.nowMs = PublicOctUploadRateLimitFilter.HEARTBEAT_REFILL_INTERVAL_MS + 1L;
        assertEquals(200, invoke(filter, "10.0.0.12", heartbeat).getStatus());
    }

    @Test
    void guardsPathsUnderTheDeployedContextPath() throws Exception {
        // Production serves the app under /LibreClinica, so getRequestURI()
        // carries the prefix; the guard must still engage.
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invokeInContext(filter, "10.0.0.20").getStatus());
        }
        assertEquals(429, invokeInContext(filter, "10.0.0.20").getStatus());
    }

    @Test
    void theInternalDeploymentIsNeverThrottled() throws Exception {
        // Off unless internet-facing: the acquisition-PC uploaders resolve every
        // capture and file it without a visit on a 429.
        PublicOctUploadRateLimitFilter filter = new PublicOctUploadRateLimitFilter(false);
        for (int i = 0; i < 10 * PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invokeInContext(filter, "10.0.0.30").getStatus(), "request " + i);
        }
    }

    @Test
    void theBeanThrottlesOnlyWhenInternetFacing() {
        at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig config =
                new at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig();
        assertTrue(config.publicOctUploadRateLimitFilter(true).isEnabled());
        assertFalse(config.publicOctUploadRateLimitFilter(false).isEnabled());
    }

    /* ---- DR-040 phase 2: a request that carries an uploader token ---------- */

    private static final String RESOLVE = "/pages/api/v1/public/upload/resolve";
    private static final String COMMIT = "/pages/api/v1/public/upload/commit";
    private static final String HEARTBEAT = "/pages/api/v1/device/uploader/heartbeat";

    private static Source source(long id) {
        return new Source(id, "uploader", null, "PC " + id, 1, "abcd1234", "active");
    }

    /** A request as the token filter leaves it: with the source of the token it carried. */
    private static MockHttpServletResponse invokeAs(PublicOctUploadRateLimitFilter filter, Source source,
                                                    String remoteAddr, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(remoteAddr);
        if (source != null) req.setAttribute(UploaderSources.ATTRIBUTE, source);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    @Test
    void aKnownUploaderGetsAClinicDaysLookupsAndSpendsNothingOfTheAddresssBudget() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, pc, "10.1.0.1", RESOLVE).getStatus(), "lookup " + i);
        }
        MockHttpServletResponse refused = invokeAs(filter, pc, "10.1.0.1", RESOLVE);
        assertEquals(429, refused.getStatus());
        assertTrue(Integer.parseInt(refused.getHeader("Retry-After")) >= 1);
        assertEquals(600, PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR);
        assertEquals(0, filter.currentSourceTokens(7, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        // The address's own budget is the strangers' one, and was never touched.
        assertEquals(PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR,
                filter.currentTokens("10.1.0.1", PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        assertEquals(200, invoke(filter, "10.1.0.1", RESOLVE).getStatus(), "a stranger at that address starts fresh");
    }

    @Test
    void theCommitsOfAKnownUploaderHaveALargerBudgetOfTheirOwn() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_COMMITS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, pc, "10.1.0.2", COMMIT).getStatus(), "commit " + i);
        }
        assertEquals(1200, PublicOctUploadRateLimitFilter.MAX_SOURCE_COMMITS_PER_HOUR);
        assertEquals(429, invokeAs(filter, pc, "10.1.0.2", COMMIT).getStatus());
        // Spent commits leave the lookups alone, and the other way round.
        assertEquals(200, invokeAs(filter, pc, "10.1.0.2", RESOLVE).getStatus());
        assertEquals(PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR - 1,
                filter.currentSourceTokens(7, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        assertEquals(0, filter.currentSourceCommitTokens(7));
    }

    @Test
    void theHeartbeatsOfAKnownUploaderKeepTheirBudgetPerSource() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_HEARTBEATS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, pc, "10.1.0.3", HEARTBEAT).getStatus(), "heartbeat " + i);
        }
        assertEquals(429, invokeAs(filter, pc, "10.1.0.3", HEARTBEAT).getStatus());
        assertEquals(200, invokeAs(filter, source(8), "10.1.0.3", HEARTBEAT).getStatus(),
                "the other program on that PC reports on its own budget");
        assertEquals(200, invokeAs(filter, pc, "10.1.0.3", RESOLVE).getStatus(), "never the lookups' budget");
    }

    @Test
    void twoUploadersBehindOneAddressDoNotShareABudget() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR; i++) {
            invokeAs(filter, source(1), "10.1.0.4", RESOLVE);
        }
        assertEquals(429, invokeAs(filter, source(1), "10.1.0.4", RESOLVE).getStatus());
        assertEquals(200, invokeAs(filter, source(2), "10.1.0.4", RESOLVE).getStatus(),
                "another uploader behind the same address is untouched");
        assertEquals(PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR - 1,
                filter.currentSourceTokens(2, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
    }

    @Test
    void oneUploaderKeepsItsBudgetWhateverAddressItComesFrom() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR; i++) {
            invokeAs(filter, source(1), "10.1.0." + (i % 200 + 1), RESOLVE);
        }
        assertEquals(429, invokeAs(filter, source(1), "192.0.2.77", RESOLVE).getStatus(),
                "a new address (a new DHCP lease) is not a new budget");
    }

    @Test
    void aRequestWithoutASourceStaysOnThePerAddressBudgetAndLeavesTheSourceBucketsAlone() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, null, "10.1.0.5", RESOLVE).getStatus());
        }
        assertEquals(429, invokeAs(filter, null, "10.1.0.5", RESOLVE).getStatus());
        assertEquals(PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR,
                filter.currentSourceTokens(1, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        // and a commit of a stranger still has the old 300
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_COMMITS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, null, "10.1.0.6", COMMIT).getStatus());
        }
        assertEquals(429, invokeAs(filter, null, "10.1.0.6", COMMIT).getStatus());
    }

    @Test
    void whereTheLimiterIsOffNoDeviceIsThrottledTokenedOrNot() throws Exception {
        // The internal deployment switches the limiter off (#404): a known uploader is not throttled there either.
        PublicOctUploadRateLimitFilter off = new PublicOctUploadRateLimitFilter(false);
        Source pc = source(7);
        for (int i = 0; i < 3 * PublicOctUploadRateLimitFilter.MAX_SOURCE_COMMITS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(off, pc, "10.1.0.7", RESOLVE).getStatus(), "lookup " + i);
            assertEquals(200, invokeAs(off, pc, "10.1.0.7", COMMIT).getStatus(), "commit " + i);
        }
        for (int i = 0; i < 3 * PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(off, null, "10.1.0.7", RESOLVE).getStatus(), "stranger " + i);
        }
    }

    @Test
    void sourceBucketsRefillAtTheirOwnRates() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR; i++) {
            invokeAs(filter, pc, "10.1.0.8", RESOLVE);
        }
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_COMMITS_PER_HOUR; i++) {
            invokeAs(filter, pc, "10.1.0.8", COMMIT);
        }
        assertEquals(429, invokeAs(filter, pc, "10.1.0.8", RESOLVE).getStatus());
        assertEquals(429, invokeAs(filter, pc, "10.1.0.8", COMMIT).getStatus());

        filter.nowMs = PublicOctUploadRateLimitFilter.SOURCE_COMMIT_REFILL_INTERVAL_MS + 1L;
        assertEquals(200, invokeAs(filter, pc, "10.1.0.8", COMMIT).getStatus(), "a commit token every 3 s");
        assertEquals(429, invokeAs(filter, pc, "10.1.0.8", RESOLVE).getStatus(), "the lookups are slower");
        filter.nowMs = PublicOctUploadRateLimitFilter.SOURCE_LOOKUP_REFILL_INTERVAL_MS + 1L;
        assertEquals(200, invokeAs(filter, pc, "10.1.0.8", RESOLVE).getStatus(), "a lookup token every 6 s");
    }

    @Test
    void theOlderPortalsOfAKnownUploaderShareOneLookupBudget() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        String image = "/pages/api/v1/public/image-upload/commit";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR; i++) {
            assertEquals(200, invokeAs(filter, pc, "10.1.0.9", image).getStatus());
        }
        assertEquals(429, invokeAs(filter, pc, "10.1.0.9", image).getStatus());
        assertEquals(200, invokeAs(filter, pc, "10.1.0.9", "/pages/api/v1/public/oct-upload/commit").getStatus(),
                "a portal of its own, as for strangers");
    }

    @Test
    void anEscapedLetterOrAPathParameterDoesNotTakeARequestOutOfItsBucket() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        Source pc = source(7);
        String disguised = "/LibreClinica/pages/api/v1/public/%75pload/commit;jsessionid=ABC";
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_SOURCE_COMMITS_PER_HOUR; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", disguised);
            req.setContextPath("/LibreClinica");
            req.setRequestURI(disguised);
            req.setAttribute(UploaderSources.ATTRIBUTE, pc);
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }
        assertEquals(0, filter.currentSourceCommitTokens(7), "counted as the commits they are");
        assertEquals(429, invokeAs(filter, pc, "10.1.0.10", COMMIT).getStatus());

        // The same for a stranger, whose commits have the old 300.
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_COMMITS_PER_HOUR; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", disguised);
            req.setContextPath("/LibreClinica");
            req.setRequestURI(disguised);
            req.setRemoteAddr("10.1.0.11");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }
        assertEquals(429, invoke(filter, "10.1.0.11", COMMIT).getStatus());
    }

    @Test
    void evictIdleDropsTheBucketsOfUploadersToo() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        invokeAs(filter, source(7), "10.1.0.12", RESOLVE);
        assertEquals(PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR - 1,
                filter.currentSourceTokens(7, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
        filter.nowMs = PublicOctUploadRateLimitFilter.IDLE_BUCKET_TTL_MS + 1L;
        filter.evictIdle();
        assertEquals(PublicOctUploadRateLimitFilter.MAX_SOURCE_LOOKUPS_PER_HOUR,
                filter.currentSourceTokens(7, PublicOctUploadRateLimitFilter.UPLOAD_PREFIX));
    }

    private static MockHttpServletResponse invokeInContext(PublicOctUploadRateLimitFilter filter,
                                                           String remoteAddr) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/LibreClinica/pages/api/v1/public/oct-upload/resolve");
        req.setContextPath("/LibreClinica");
        req.setRequestURI("/LibreClinica/pages/api/v1/public/oct-upload/resolve");
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    /* ---- helpers ----------------------------------------------------- */

    private static MockHttpServletResponse invoke(PublicOctUploadRateLimitFilter filter,
                                                   String remoteAddr, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    private static MockHttpServletRequest guardedRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/pages/api/v1/public/oct-upload/commit");
        req.setRequestURI("/pages/api/v1/public/oct-upload/commit");
        return req;
    }

    private static MockHttpServletResponse invoke(PublicOctUploadRateLimitFilter filter,
                                                   String remoteAddr) throws Exception {
        MockHttpServletRequest req = guardedRequest();
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    /** Filter subclass that uses an injectable clock instead of wall time. */
    private static final class ClockableFilter extends PublicOctUploadRateLimitFilter {
        long nowMs;
        ClockableFilter(long initialMs) {
            this.nowMs = initialMs;
        }
        @Override
        long nowMs() {
            return nowMs;
        }
    }
}
