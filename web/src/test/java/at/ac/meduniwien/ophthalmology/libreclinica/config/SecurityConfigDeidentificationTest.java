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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.InternetFacingPathBlockFilter;

/**
 * De-identification required, but NOT internet-facing: the account-less upload
 * routes and the DICOM C-STORE hand-off are closed all the same, and the rest
 * of the internal deployment is untouched.
 */
class SecurityConfigDeidentificationTest {

    private static final List<String> CLOSED = List.of(
            "/pages/api/v1/public/upload/commit",
            "/pages/api/v1/public/upload/resolve",
            "/pages/api/v1/public/upload/preflight",
            "/pages/api/v1/public/oct-upload/commit",
            "/pages/api/v1/public/oct-upload/resolve",
            "/pages/api/v1/public/image-upload/commit",
            "/pages/api/v1/internal/dicom-ingest");

    /** Still reachable on the internal deployment: not file uploads. */
    private static final List<String> OPEN = List.of(
            "/pages/api/v1/public/bcva-entry/commit",
            "/pages/api/v1/internal/dicom-worklist",
            "/pages/api/v1/device/uploader/heartbeat",
            "/actuator/info",
            "/actuator/health");

    @RestController
    @RequestMapping("/**")
    static class Probe {
        @RequestMapping
        String any() {
            return "reached";
        }
    }

    private static HttpSecurity base(HttpSecurity http, boolean deid) throws Exception {
        return http.csrf(c -> c.disable()).anonymous(a -> { })
                .exceptionHandling(eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(SecurityConfig.authorization(false, false, deid));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Required {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            base(http, true);
            http.addFilterBefore(new InternetFacingPathBlockFilter(SecurityConfig.DEIDENTIFICATION_CLOSED_PATHS),
                    SecurityConfig.RATE_LIMIT_ANCHOR);
            return http.build();
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class RulesOnly {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return base(http, true).build();
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class NotRequired {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return base(http, false).build();
        }
    }

    private static MockMvc mvc(Class<?> config) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(config);
        ctx.refresh();
        return MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", FilterChainProxy.class)).build();
    }

    private static int status(MockMvc mvc, String method, String path) throws Exception {
        return mvc.perform("POST".equals(method) ? post(path) : get(path)).andReturn().getResponse().getStatus();
    }

    @Test
    void requiredAloneAnswers404ForTheUploadRoutesAndTheCStoreHandoff() throws Exception {
        MockMvc mvc = mvc(Required.class);
        for (String p : CLOSED) {
            assertEquals(404, status(mvc, "GET", p), "GET " + p);
            assertEquals(404, status(mvc, "POST", p), "POST " + p);
        }
        for (String p : OPEN) {
            assertEquals(200, status(mvc, "GET", p), "GET " + p);
        }
    }

    @Test
    void theAuthorizationRulesAloneDenyTheSamePaths() throws Exception {
        MockMvc mvc = mvc(RulesOnly.class);
        for (String p : CLOSED) {
            int s = status(mvc, "POST", p);
            assertTrue(s == 401 || s == 403, p + " -> " + s);
        }
    }

    @Test
    void notRequiredLeavesEverythingAsItWas() throws Exception {
        MockMvc mvc = mvc(NotRequired.class);
        for (String p : CLOSED) {
            assertEquals(200, status(mvc, "POST", p), p);
        }
    }
}
