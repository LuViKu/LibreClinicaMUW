/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses state-changing requests that a browser sends on behalf of another
 * site — the token-free CSRF defence for the whole application.
 *
 * <p>Spring Security's token CSRF stays disabled (see {@code SecurityConfig}):
 * several hundred heritage JSP forms and servlets post without a token, and
 * retrofitting one into each is out of proportion. This filter instead asks
 * the browser where the request came from:
 *
 * <ol>
 *   <li>Safe methods (GET, HEAD, OPTIONS, TRACE) pass untouched.</li>
 *   <li>When the browser sends Fetch Metadata ({@code Sec-Fetch-Site}), only
 *       {@code same-origin} and {@code none} (typed URL, bookmark) pass;
 *       {@code cross-site} and {@code same-site} are refused. Same-site is
 *       refused on purpose: every host under the institutional domain is the
 *       same "site", and none of them has any business posting here.</li>
 *   <li>Without Fetch Metadata, an {@code Origin} header must name this
 *       request's own origin (scheme, host, port as Tomcat sees them; behind
 *       nginx the RemoteIpValve supplies the https scheme and the forwarded
 *       Host). {@code Origin: null} is refused.</li>
 *   <li>With neither header the request passes: that is a non-browser client
 *       — the acquisition-PC uploaders, the DICOM receiver, API-key callers —
 *       and those carry no ambient session cookie a third site could ride on.</li>
 * </ol>
 *
 * <p>The session cookie is additionally issued with {@code SameSite=Lax}
 * ({@code META-INF/context.xml}), which keeps it off cross-site sub-requests
 * and cross-site POSTs even in a browser that sends no Fetch Metadata.
 *
 * <p>Nothing legitimate reaches the application cross-site: SSO login is
 * handled by the reverse proxy (the SAML POST lands on the proxy's own
 * {@code /Shibboleth.sso} handler, never on the WAR, and the proxy then
 * redirects with a GET), and the public upload pages are served by this same
 * origin.
 *
 * <p>Reads headers and the request URI only — never parameters — so it can sit
 * ahead of the character-encoding filter without fixing the body encoding.
 */
public final class CrossSiteRequestFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(CrossSiteRequestFilter.class);

    static final String SEC_FETCH_SITE = "Sec-Fetch-Site";
    static final String ORIGIN = "Origin";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> TRUSTED_FETCH_SITES = Set.of("same-origin", "none");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (isAllowed(req)) {
            chain.doFilter(request, response);
            return;
        }
        refuse(req, (HttpServletResponse) response);
    }

    /** Package-private for the unit test. */
    static boolean isAllowed(HttpServletRequest req) {
        String method = req.getMethod();
        if (method == null || SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        String fetchSite = req.getHeader(SEC_FETCH_SITE);
        if (fetchSite != null) {
            return TRUSTED_FETCH_SITES.contains(fetchSite.trim().toLowerCase(Locale.ROOT));
        }
        String origin = req.getHeader(ORIGIN);
        if (origin == null) {
            return true;
        }
        return isSameOrigin(origin.trim(), req);
    }

    static boolean isSameOrigin(String origin, HttpServletRequest req) {
        if (origin.isEmpty() || "null".equals(origin)) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        int port = uri.getPort() == -1 ? defaultPort(scheme) : uri.getPort();
        String requestScheme = req.getScheme();
        String requestHost = req.getServerName();
        if (requestScheme == null || requestHost == null) {
            return false;
        }
        return scheme.equalsIgnoreCase(requestScheme)
                && stripBrackets(host).equalsIgnoreCase(stripBrackets(requestHost))
                && port == req.getServerPort();
    }

    private static int defaultPort(String scheme) {
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http":
                return 80;
            case "https":
                return 443;
            default:
                return -1;
        }
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static void refuse(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        LOG.warn("Refused cross-site {} {} (Sec-Fetch-Site={}, Origin={})",
                clean(req.getMethod()), clean(req.getRequestURI()),
                clean(req.getHeader(SEC_FETCH_SITE)), clean(req.getHeader(ORIGIN)));
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String uri = req.getRequestURI();
        if (uri != null && uri.contains("/api/")) {
            resp.setContentType("application/json");
            resp.getWriter().write("{\"message\":\"Cross-site request refused\"}");
        } else {
            resp.setContentType("text/plain");
            resp.getWriter().write("Cross-site request refused.");
        }
    }

    /** Header and URI values are attacker-chosen: no line breaks into the log, bounded length. */
    private static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String flat = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "_");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
