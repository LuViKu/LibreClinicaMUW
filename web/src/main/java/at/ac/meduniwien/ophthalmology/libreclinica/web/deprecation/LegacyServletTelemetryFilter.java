/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase E.8 legacy-retirement (2026-06-20) — log every hit on a
 * legacy servlet registered in {@link LegacyServletDeprecationCatalog}
 * + optionally short-circuit with {@code 410 Gone} when the kill-
 * switch is engaged.
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li>Every request matching a catalog entry emits one INFO line
 *       on the {@code legacy-access} logger with structured MDC fields
 *       {@code legacyPath}, {@code legacyBucket}, {@code spaRoute},
 *       {@code user}, {@code reqId}. Ops can grep / aggregate / page
 *       on these without parsing free text.</li>
 *   <li>When {@link #killSwitchEnabled} (env
 *       {@code LIBRECLINICA_LEGACY_SERVLETS_ENABLED=false}) is set,
 *       cataloged hits get a {@code 410 Gone} response with a JSON
 *       body pointing at the SPA route. Non-cataloged requests are
 *       always passed through — the kill switch is scoped to the
 *       "safe to delete" set so login + admin tooling keeps working
 *       during the grace period.</li>
 *   <li>Requests with no catalog entry are passed through unmodified
 *       — no per-request lookup overhead beyond a hash-map probe.</li>
 * </ul>
 *
 * <p>Registered in {@code ServletInfraConfig} at a lower precedence
 * than {@link at.ac.meduniwien.ophthalmology.libreclinica.web.filter.RequestIdFilter}
 * so the {@code reqId} MDC value is already populated when this filter
 * logs.
 */
public class LegacyServletTelemetryFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger("legacy-access");

    /**
     * Request-attribute keys read by the SiteMesh decorator
     * ({@code decorator.jsp}) to render the deprecation banner. Set
     * only when {@link #bannerEnabled} is true AND the request hit a
     * catalog entry.
     */
    public static final String ATTR_BANNER_VISIBLE = "muw.legacyDeprecation.bannerVisible";
    public static final String ATTR_SPA_ROUTE = "muw.legacyDeprecation.spaRoute";
    public static final String ATTR_BUCKET = "muw.legacyDeprecation.bucket";
    public static final String ATTR_SUNSET_DATE = "muw.legacyDeprecation.sunsetDate";

    private final LegacyServletDeprecationCatalog catalog;
    private final boolean servletsEnabled;
    private final boolean bannerEnabled;
    private final String sunsetDate;

    public LegacyServletTelemetryFilter(LegacyServletDeprecationCatalog catalog,
                                        boolean servletsEnabled,
                                        boolean bannerEnabled,
                                        String sunsetDate) {
        this.catalog = catalog;
        this.servletsEnabled = servletsEnabled;
        this.bannerEnabled = bannerEnabled;
        this.sunsetDate = sunsetDate;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq) || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Entry> hit = catalog.lookup(httpReq.getRequestURI());
        if (hit.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Entry entry = hit.get();
        String user = httpReq.getRemoteUser();
        LOG.info("legacy-hit path={} bucket={} spaRoute={} method={} user={}",
                entry.legacyPath(), entry.bucket(), entry.spaRoute(),
                httpReq.getMethod(),
                user == null ? "anonymous" : user);

        if (!servletsEnabled) {
            httpResp.setStatus(HttpServletResponse.SC_GONE);
            httpResp.setContentType("application/json");
            try (PrintWriter w = httpResp.getWriter()) {
                w.write("{\"message\":\"This URL has been retired.\","
                        + "\"legacyPath\":\"" + entry.legacyPath() + "\","
                        + "\"spaRoute\":\"" + entry.spaRoute() + "\","
                        + "\"bucket\":\"" + entry.bucket() + "\"}");
            }
            return;
        }

        if (bannerEnabled) {
            httpReq.setAttribute(ATTR_BANNER_VISIBLE, Boolean.TRUE);
            httpReq.setAttribute(ATTR_SPA_ROUTE, entry.spaRoute());
            httpReq.setAttribute(ATTR_BUCKET, entry.bucket().name());
            httpReq.setAttribute(ATTR_SUNSET_DATE, sunsetDate);
        }

        chain.doFilter(request, response);
    }
}
