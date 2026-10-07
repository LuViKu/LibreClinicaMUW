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
import at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers a bare {@code 404} for a fixed set of paths, whoever the caller is.
 *
 * <p>Used when {@code libreclinica.deployment.internet-facing=true} to take the
 * account-less upload portals, device and sidecar APIs and the operational
 * endpoints off the air. A plain deny in the authorization rules would answer
 * 401 / 302 / 403 and so confirm the path exists; a 404 with no body is what an
 * unmapped path gives. Runs inside the Spring Security chain, ahead of the
 * rate-limit filter and every controller.
 */
public class InternetFacingPathBlockFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(InternetFacingPathBlockFilter.class);

    private final RequestMatcher[] blocked;

    public InternetFacingPathBlockFilter(String... patterns) {
        this.blocked = new RequestMatcher[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            this.blocked[i] = SecurityConfig.pathPattern(patterns[i]);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        for (RequestMatcher m : blocked) {
            if (m.matches(request)) {
                LOG.debug("internet-facing: blocked {} {}", request.getMethod(), request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
