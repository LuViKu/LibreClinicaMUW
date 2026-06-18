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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    void prefersXForwardedForOverRemoteAddr() throws Exception {
        ClockableFilter filter = new ClockableFilter(0L);
        for (int i = 0; i < PublicOctUploadRateLimitFilter.MAX_REQUESTS_PER_HOUR; i++) {
            MockHttpServletRequest req = guardedRequest();
            req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            assertEquals(200, resp.getStatus());
        }
        // XFF IP exhausted; an XFF-less request from the same backend
        // remote IP (10.0.0.1) is in a separate bucket and passes.
        MockHttpServletRequest direct = guardedRequest();
        direct.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(direct, resp, new MockFilterChain());
        assertEquals(200, resp.getStatus());
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

    /* ---- helpers ----------------------------------------------------- */

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
