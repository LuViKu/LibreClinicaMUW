/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Phase E hardening A4 — unit coverage for {@link RequestIdFilter}.
 *
 * Three cases: well-formed inbound id is accepted verbatim, missing
 * header → fresh UUIDv4 minted, malformed inbound (too long / illegal
 * chars / blank) → fresh UUIDv4 minted. Every case asserts MDC is
 * populated during the chain and cleared afterward, and the response
 * header echoes the resolved id.
 */
class RequestIdFilterTest {

    @AfterEach
    void clearMdc() {
        // Defensive — every test path should leave MDC empty, but if a
        // failure occurs mid-chain we don't want to pollute the next test.
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void wellFormedInboundIdFlowsThroughChainAndIsEchoed() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER_NAME, "testabc123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> mdcAtChain = new AtomicReference<>();
        FilterChain chain = capturingChain(mdcAtChain);

        filter.doFilter(req, resp, chain);

        assertEquals("testabc123", mdcAtChain.get(), "MDC must hold inbound id during chain");
        assertEquals("testabc123", resp.getHeader(RequestIdFilter.HEADER_NAME),
                "Response must echo inbound id");
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleared after chain");
    }

    @Test
    void missingHeaderMintsFreshUuid() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> mdcAtChain = new AtomicReference<>();

        filter.doFilter(req, resp, capturingChain(mdcAtChain));

        String generated = mdcAtChain.get();
        assertNotNull(generated, "MDC must hold a generated id during chain");
        assertValidUuid(generated);
        assertEquals(generated, resp.getHeader(RequestIdFilter.HEADER_NAME),
                "Response header must match generated id");
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleared after chain");
    }

    @Test
    void overLongInboundIdIsReplacedWithFreshUuid() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER_NAME, "a".repeat(RequestIdFilter.MAX_ID_LENGTH + 1));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> mdcAtChain = new AtomicReference<>();

        filter.doFilter(req, resp, capturingChain(mdcAtChain));

        String generated = mdcAtChain.get();
        assertNotNull(generated);
        assertValidUuid(generated);
        assertEquals(generated, resp.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void illegalCharsInInboundIdReplacedWithFreshUuid() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        // Whitespace + control chars are the log-injection vector we reject.
        req.addHeader(RequestIdFilter.HEADER_NAME, "bad id\r\nFAKE");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> mdcAtChain = new AtomicReference<>();

        filter.doFilter(req, resp, capturingChain(mdcAtChain));

        String generated = mdcAtChain.get();
        assertNotNull(generated);
        assertValidUuid(generated);
        assertEquals(generated, resp.getHeader(RequestIdFilter.HEADER_NAME));
    }

    @Test
    void blankInboundIdReplacedWithFreshUuid() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER_NAME, "   ");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> mdcAtChain = new AtomicReference<>();

        filter.doFilter(req, resp, capturingChain(mdcAtChain));

        String generated = mdcAtChain.get();
        assertValidUuid(generated);
        assertEquals(generated, resp.getHeader(RequestIdFilter.HEADER_NAME));
    }

    @Test
    void mdcIsClearedEvenWhenChainThrows() {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER_NAME, "deadbeef");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest r, ServletResponse s) throws ServletException {
                throw new ServletException("boom");
            }
        };

        try {
            ((Filter) filter).doFilter(req, resp, chain);
        } catch (Exception expected) {
            // ignored — we only care that finally clears MDC
        }
        assertNull(MDC.get(RequestIdFilter.MDC_KEY),
                "MDC must be cleared even when downstream throws");
        assertEquals("deadbeef", resp.getHeader(RequestIdFilter.HEADER_NAME),
                "Response header is set before the chain runs and survives the throw");
    }

    private static FilterChain capturingChain(AtomicReference<String> sink) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                sink.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        };
    }

    private static void assertValidUuid(String value) {
        assertNotNull(value);
        // UUID.fromString throws IllegalArgumentException on non-UUIDs.
        UUID parsed = UUID.fromString(value);
        assertTrue(parsed.version() == 4, "expected UUIDv4, got version " + parsed.version());
    }
}
