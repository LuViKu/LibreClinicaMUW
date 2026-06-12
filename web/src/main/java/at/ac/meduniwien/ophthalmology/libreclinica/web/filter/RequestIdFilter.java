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
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase E hardening A4 — request-correlation trace ID.
 *
 * <p>End-to-end correlation: every request gets a stable id that is
 * propagated through {@link MDC} (so it lands in {@code logback.xml}'s
 * {@code [%X{reqId:-}]} pattern), echoed in the response header
 * ({@code X-Request-Id}), and read back by the SPA's {@code api/client.ts}
 * so the operator can paste the id from a {@code GlobalErrorToast} into
 * a bug report. Combined with the A1 failure-audit row (which persists
 * the same id), this closes the SPA console → server log → audit row chain.
 *
 * <h2>Header policy</h2>
 * <ul>
 *   <li>If the inbound request carries {@code X-Request-Id} and it is
 *       non-blank, ≤ 64 chars, and matches {@code [A-Za-z0-9-]+}, it is
 *       accepted verbatim. The 64-char cap + restricted charset reject
 *       log-injection and oversize payloads.</li>
 *   <li>Otherwise a fresh {@link UUID#randomUUID() UUIDv4} is generated.</li>
 * </ul>
 *
 * <h2>Filter ordering</h2>
 * Registered in {@code ServletInfraConfig.requestIdFilter()} at
 * {@code Ordered.HIGHEST_PRECEDENCE} — strictly ahead of every other
 * filter in the chain (CharacterEncodingFilter, LocaleFilter,
 * SecurityFilterChain, OpenEntityManagerInViewFilter, OCServletFilter,
 * ApiSecurityFilter). MDC must be populated before any downstream filter
 * logs so the {@code [%X{reqId:-}]} pattern carries the id for the entire
 * request lifecycle.
 *
 * <h2>MDC hygiene</h2>
 * MDC is a {@link ThreadLocal} keyed by thread, not request — leaking
 * the id past chain completion would pollute the next request on the
 * same Tomcat worker thread. The {@code finally} block removes the key
 * unconditionally.
 */
public final class RequestIdFilter implements Filter {

    /** MDC key surfaced in {@code logback.xml} as {@code %X{reqId:-}}. */
    public static final String MDC_KEY = "reqId";

    /** Response header carrying the resolved id back to the SPA. */
    public static final String HEADER_NAME = "X-Request-Id";

    /** Upper bound on inbound id length. Matches the SPA's UUIDv4 length (36) with headroom. */
    static final int MAX_ID_LENGTH = 64;

    /** Acceptable id charset — UUIDs, opaque hex, slugs. Rejects whitespace + log-injection chars. */
    static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9-]+");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String id = resolveRequestId(req.getHeader(HEADER_NAME));

        MDC.put(MDC_KEY, id);
        resp.setHeader(HEADER_NAME, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accept a well-formed inbound id verbatim; otherwise mint a fresh
     * UUIDv4. Package-private for unit-test reach.
     */
    static String resolveRequestId(String inbound) {
        if (inbound != null) {
            String trimmed = inbound.trim();
            if (!trimmed.isEmpty()
                    && trimmed.length() <= MAX_ID_LENGTH
                    && ID_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
