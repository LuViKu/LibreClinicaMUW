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

import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyAccessLog.Action;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Legacy-retirement tracking (DR-018; plan R0.3): logs every request for a
 * legacy screen, so that the six-month bake-in can show whether a screen is
 * still used.
 *
 * <h2>What it sees</h2>
 *
 * <p>Registered on {@code /*} for {@code REQUEST} dispatches
 * ({@code ServletInfraConfig}). For each request it looks the container's own
 * mapping — {@code getServletPath()} and {@code getPathInfo()}, which exclude
 * the context path — up in {@link LegacyServletDeprecationCatalog}: every
 * servlet registered by {@code LegacyServletRegistry} and every Spring MVC
 * route under {@code /pages} that renders a JSP. A request for anything else
 * costs one hash probe and passes untouched.
 *
 * <p>Until 2026-09-30 the filter was registered on {@code /pages/*}, which no
 * legacy servlet is mapped under, and compared the request URI, context path
 * included, with keys that had none. It recorded nothing.
 *
 * <h2>What it does with a legacy request</h2>
 *
 * <p>It logs one {@code legacy-hit} line on the {@code legacy-access} logger
 * (format in {@link LegacyAccessLog}) and passes the request on unchanged.
 *
 * <p>The earlier switches are gone. {@code libreclinica.legacy.servletsEnabled}
 * was meant to answer 410 for every catalogued screen at once; it never took
 * effect, because the filter never matched a request.
 * {@code libreclinica.legacy.banner} and {@code libreclinica.legacy.sunsetDate}
 * fed a banner in the SiteMesh decorator, which has not run since SiteMesh
 * left the build. Setting any of the three has no effect.
 *
 * <h2>Where it sits</h2>
 *
 * <p>Second in the chain, after {@code RequestIdFilter} (so each line carries
 * the {@code reqId}) and ahead of Spring Security, so that an unauthenticated
 * request is recorded too before security sends it to the login page. The
 * user comes from the session attribute {@code userBean}, where the login
 * filter puts the signed-in user and where the legacy servlets and SPA
 * controllers read it from. An SSO session that has not yet reached the SPA
 * or a legacy page has no {@code userBean} and is logged as anonymous.
 *
 * <h2>Dispatcher types</h2>
 *
 * <p>{@code REQUEST} only: a legacy page that forwards to or includes another
 * screen server-side is one request, logged once, under the URL the browser
 * asked for.
 */
public class LegacyServletTelemetryFilter implements Filter {

    private final LegacyServletDeprecationCatalog catalog;

    public LegacyServletTelemetryFilter(LegacyServletDeprecationCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpReq) {
            Entry entry = catalog.lookup(httpReq.getServletPath(), httpReq.getPathInfo()).orElse(null);
            if (entry != null) {
                LegacyAccessLog.hit(entry, httpReq, LegacyAccessLog.sessionUser(httpReq), false, Action.PASS);
            }
        }
        chain.doFilter(request, response);
    }
}
