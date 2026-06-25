/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Phase E.8 legacy-retirement (2026-06-20) — unit tests for the
 * pass-through / banner-attrs / kill-switch behaviour of the
 * telemetry filter.
 */
class LegacyServletTelemetryFilterTest {

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    @Test
    void unmappedPathPassesThroughUntouched() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/api/v1/subjects");
        req.setRequestURI("/pages/api/v1/subjects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog,
                /* servletsEnabled */ true,
                /* bannerEnabled   */ true,
                /* sunsetDate      */ "2026-08-15")
                .doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertNull(req.getAttribute(LegacyServletTelemetryFilter.ATTR_BANNER_VISIBLE));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void catalogHitSetsBannerAttrs() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/ListStudySubjects");
        req.setRequestURI("/pages/ListStudySubjects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog, true, true, "2026-08-15")
                .doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertNotNull(req.getAttribute(LegacyServletTelemetryFilter.ATTR_BANNER_VISIBLE));
        assertEquals("/app/subjects",
                req.getAttribute(LegacyServletTelemetryFilter.ATTR_SPA_ROUTE));
        assertEquals("SUBJECTS_AND_EVENTS",
                req.getAttribute(LegacyServletTelemetryFilter.ATTR_BUCKET));
        assertEquals("2026-08-15",
                req.getAttribute(LegacyServletTelemetryFilter.ATTR_SUNSET_DATE));
    }

    @Test
    void bannerDisabledLeavesAttrsUnset() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/ListStudySubjects");
        req.setRequestURI("/pages/ListStudySubjects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog, true, /* banner */ false, "2026-08-15")
                .doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertNull(req.getAttribute(LegacyServletTelemetryFilter.ATTR_BANNER_VISIBLE));
    }

    @Test
    void killSwitchShortCircuitsCatalogHitWith410() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/ListStudySubjects");
        req.setRequestURI("/pages/ListStudySubjects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog,
                /* servletsEnabled */ false, true, "2026-08-15")
                .doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(HttpServletResponse.SC_GONE, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("\"spaRoute\":\"/app/subjects\""));
        assertTrue(body.contains("\"bucket\":\"SUBJECTS_AND_EVENTS\""));
    }

    @Test
    void killSwitchDoesNotAffectUnmappedPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/login/login");
        req.setRequestURI("/pages/login/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog, false, true, "2026-08-15")
                .doFilter(req, resp, chain);

        // /pages/login/login is intentionally NOT in the catalog;
        // the kill switch must not break login during the grace period.
        verify(chain, times(1)).doFilter(any(), any());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void nonHttpRequestPassesThrough() throws Exception {
        jakarta.servlet.ServletRequest req = Mockito.mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse resp = Mockito.mock(jakarta.servlet.ServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);

        new LegacyServletTelemetryFilter(catalog, true, true, "2026-08-15")
                .doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    /** Silence unused-import warnings for stub helpers. */
    @SuppressWarnings("unused")
    private static final Class<HttpServletRequest> UNUSED = HttpServletRequest.class;
}
