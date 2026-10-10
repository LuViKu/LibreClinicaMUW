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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.ServletRequestPathUtils;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 400 to a request whose path Spring 7 cannot parse, instead of the 500
 * its path parsing produces.
 *
 * <p>Tomcat decodes a percent-encoded letter before it maps the request, so
 * {@code /LibreClinica/%70ages/api/v1/me} reaches the {@code pages} servlet
 * with servlet path {@code /pages}. Spring 7 builds the request path from the
 * raw URI and the servlet path, finds the context path no longer a prefix of
 * it, and throws {@link IllegalArgumentException}. That happens in the first
 * filter of the security chain ({@code ServletRequestPathFilter}, before the
 * firewall and every rule) and again in the dispatcher, so only a filter ahead
 * of both can turn it into an ordinary refusal.
 *
 * <p>Such a request was never served: it failed closed with a 500. This changes
 * only the status it gets, and cannot change an allow or deny decision. The
 * parse is the one Spring does, cached on the request and cleared again, so a
 * request that parses reaches the rest of the chain exactly as before. The
 * response is the bare status; the path is not echoed.
 */
public final class UnparseablePathFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(UnparseablePathFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse httpResponse) {
            try {
                ServletRequestPathUtils.parseAndCache(http);
            } catch (IllegalArgumentException e) {
                LOG.info("Refused a request whose path cannot be parsed: {} {}",
                        sanitize(http.getMethod()), e.getClass().getSimpleName());
                // setStatus, not sendError: an error dispatch would run the same path parsing again.
                httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                httpResponse.setContentLength(0);
                return;
            }
            ServletRequestPathUtils.clearParsedRequestPath(http);
        }
        chain.doFilter(request, response);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z]", "?");
    }
}
