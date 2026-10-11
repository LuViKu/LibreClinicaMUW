/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import jakarta.servlet.Filter;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.UploaderTokenFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.InternetFacingPathBlockFilter;

/**
 * DR-040 phase 2 — the filters in front of the account-less device endpoints,
 * as {@link SecurityConfig#addDeviceFilters} mounts them: the 404 block (when
 * the deployment has one), then the uploader-token front door, then the rate
 * limiter, all ahead of anything that touches the session.
 *
 * <p>The order is the point. The rate limiter keys a known uploader's budget by
 * the source the token filter leaves on the request, so it must run after it;
 * and an internet-facing request has to be answered 404 before the token
 * filter, or it would learn from a 401 that the path exists.
 *
 * <p>The second half pins the list: every path an anonymous device can reach
 * (the device and public entries of {@code SecurityConfig.PUBLIC_PATHS}, the BCVA
 * page apart) is guarded, so a device path added to the public list without the
 * token door fails here.
 */
class SecurityConfigDeviceFiltersTest {

    /** What the real chain gets: a filter of each kind, none of which is ever asked to filter. */
    private static final Filter BLOCK = new InternetFacingPathBlockFilter("/pages/api/v1/public/**");
    private static final UploaderTokenFilter TOKENS =
            new UploaderTokenFilter(new IngestSourceService(null), new LocationScope(null));
    private static final PublicOctUploadRateLimitFilter RATE_LIMIT = new PublicOctUploadRateLimitFilter(false);

    @Configuration
    @EnableWebSecurity
    static class WithoutBlock {
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable()).authorizeHttpRequests(SecurityConfig.authorization(false, false));
            SecurityConfig.addDeviceFilters(http, null, TOKENS, RATE_LIMIT);
            return http.logout(l -> l.logoutUrl("/j_spring_security_logout")).formLogin(f -> { }).build();
        }
    }

    @Configuration
    @EnableWebSecurity
    static class WithBlock {
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable()).authorizeHttpRequests(SecurityConfig.authorization(false, false));
            SecurityConfig.addDeviceFilters(http, BLOCK, TOKENS, RATE_LIMIT);
            return http.logout(l -> l.logoutUrl("/j_spring_security_logout")).formLogin(f -> { }).build();
        }
    }

    private static List<Class<?>> chainOf(Class<?> config) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(config);
        ctx.refresh();
        List<Class<?>> classes = new ArrayList<>();
        ctx.getBean("springSecurityFilterChain", FilterChainProxy.class).getFilterChains().get(0)
                .getFilters().forEach(f -> classes.add(f.getClass()));
        ctx.close();
        return classes;
    }

    private static void assertBefore(List<Class<?>> chain, Class<?> first, Class<?> second) {
        int a = chain.indexOf(first);
        int b = chain.indexOf(second);
        assertTrue(a >= 0, first.getSimpleName() + " is in the chain: " + chain);
        assertTrue(b >= 0, second.getSimpleName() + " is in the chain: " + chain);
        assertTrue(a < b, first.getSimpleName() + " runs before " + second.getSimpleName() + ": " + chain);
    }

    @Test
    void theTokenFilterRunsBeforeTheRateLimiterAndBothAheadOfTheSession() {
        List<Class<?>> chain = chainOf(WithoutBlock.class);
        assertEquals(1, chain.stream().filter(UploaderTokenFilter.class::equals).count(), "mounted once: " + chain);
        assertBefore(chain, DisableEncodeUrlFilter.class, UploaderTokenFilter.class);
        assertBefore(chain, UploaderTokenFilter.class, PublicOctUploadRateLimitFilter.class);
        for (Class<?> later : List.of(WebAsyncManagerIntegrationFilter.class, SecurityContextHolderFilter.class,
                HeaderWriterFilter.class, LogoutFilter.class, UsernamePasswordAuthenticationFilter.class)) {
            assertBefore(chain, PublicOctUploadRateLimitFilter.class, later);
        }
    }

    @Test
    void theBlockRunsBeforeTheTokenFilterSoAnInternetFacingPathAnswers404NotA401() {
        List<Class<?>> chain = chainOf(WithBlock.class);
        assertBefore(chain, DisableEncodeUrlFilter.class, InternetFacingPathBlockFilter.class);
        assertBefore(chain, InternetFacingPathBlockFilter.class, UploaderTokenFilter.class);
        assertBefore(chain, UploaderTokenFilter.class, PublicOctUploadRateLimitFilter.class);
        assertBefore(chain, PublicOctUploadRateLimitFilter.class, SecurityContextHolderFilter.class);
    }

    @Test
    void everyAnonymousDevicePathIsGuardedAndNothingElse() {
        Set<String> devicePaths = new TreeSet<>();
        for (String p : SecurityConfig.PUBLIC_PATHS) {
            boolean device = p.startsWith("/pages/api/v1/public/") || p.startsWith("/pages/api/v1/device/");
            if (device && !p.startsWith("/pages/api/v1/public/bcva-entry/")) devicePaths.add(p);
        }
        assertEquals(devicePaths, new TreeSet<>(UploaderTokenFilter.guardedPatterns()),
                "the public device entries and the token filter's guard are the same list");
    }
}
