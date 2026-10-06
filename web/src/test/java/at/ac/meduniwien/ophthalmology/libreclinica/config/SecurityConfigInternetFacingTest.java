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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockServletContext;

import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.InternetFacingPathBlockFilter;

/**
 * Pins the {@code libreclinica.deployment.internet-facing} switch in
 * {@link SecurityConfig}. The real authorization rules
 * ({@link SecurityConfig#authorization(boolean, boolean)}) and the real
 * {@link InternetFacingPathBlockFilter} run inside a minimal Spring Security
 * chain with a probe controller that answers 200 to anything it is handed; a
 * 200 therefore means "the request got past security".
 *
 * <p>No full Boot context: see {@link PrometheusActuatorTest} for why.
 */
class SecurityConfigInternetFacingTest {

    /** One concrete URL per denied family, bare twins included. */
    private static final List<String> DENIED = List.of(
            "/pages/api/v1/public/oct-upload/resolve",
            "/pages/api/v1/public/oct-upload/preflight",
            "/pages/api/v1/public/bcva-entry/commit",
            "/pages/api/v1/public/bcva-entry/S_X/visits",
            "/pages/api/v1/public/image-upload/visits",
            "/pages/api/v1/public/image-upload/commit",
            "/pages/api/v1/public/upload/preflight",
            "/pages/api/v1/public/upload/commit",
            "/pages/api/v1/internal/dicom-ingest",
            "/pages/api/v1/internal/dicom-worklist",
            "/pages/api/v1/device/optomed/worklist.txt",
            "/pages/api/v1/device/uploader/heartbeat",
            "/actuator/info",
            "/actuator/prometheus",
            "/pages/v3/api-docs",
            "/pages/v3/api-docs/spa-api",
            "/pages/v3/api-docs.yaml",
            "/pages/swagger-ui.html",
            "/pages/swagger-ui/index.html",
            "/pages/auth/api/v1/system/systemstatus",
            "/pages/auth/api/v1/studies/",
            "/pages/auth/api/v1/discrepancynote/dnote",
            "/SystemStatus",
            "/pages/sso/reauth");

    /** Stay reachable anonymously in both modes. */
    private static final List<String> STILL_OPEN = List.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/app/login",
            "/pages/login/login",
            "/pages/api/v1/contact");

    @RestController
    @RequestMapping("/**")
    static class Probe {
        @org.springframework.web.bind.annotation.RequestMapping
        String any() {
            return "reached";
        }
    }

    static HttpSecurity base(HttpSecurity http, boolean internetFacing, boolean sso) throws Exception {
        return http
                .csrf(c -> c.disable())
                .anonymous(a -> { })
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(SecurityConfig.authorization(internetFacing, sso));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Off {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return base(http, false, false).build();
        }
    }

    /** Flag on: 404 filter plus deny rules, as in production. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class On {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            base(http, true, false);
            http.addFilterBefore(
                    new InternetFacingPathBlockFilter(SecurityConfig.internetFacingDeniedPaths(false)),
                    org.springframework.security.web.access.channel.ChannelProcessingFilter.class);
            return http.build();
        }
    }

    /** Flag on, without the 404 filter: the authorization rules alone must deny. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class OnRulesOnly {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return base(http, true, false).build();
        }
    }

    /** Flag on and SSO enabled: as {@link On}, except the SSO re-auth endpoint stays. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class OnWithSso {
        @Bean Probe probe() { return new Probe(); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            base(http, true, true);
            http.addFilterBefore(
                    new InternetFacingPathBlockFilter(SecurityConfig.internetFacingDeniedPaths(true)),
                    org.springframework.security.web.access.channel.ChannelProcessingFilter.class);
            return http.build();
        }
    }

    @Test
    void flagOn_bareSpringdocTwinsAreAnswered404() throws Exception {
        // Not public in either mode (upstream serves springdoc under /pages only);
        // on the internet-facing deployment they answer 404 rather than 401.
        MockMvc on = mvc(On.class);
        MockMvc off = mvc(Off.class);
        for (String p : List.of("/v3/api-docs", "/v3/api-docs/spa-api", "/v3/api-docs.yaml",
                "/swagger-ui.html", "/swagger-ui/index.html")) {
            assertEquals(404, status(on, "GET", p), "flag on, GET " + p);
            assertEquals(401, status(off, "GET", p), "flag off, GET " + p);
        }
    }

    @Test
    void flagOn_withSso_keepsTheReauthEndpointAndStillClosesTheRest() throws Exception {
        MockMvc mvc = mvc(OnWithSso.class);
        assertEquals(200, status(mvc, "GET", "/pages/sso/reauth"));
        for (String p : DENIED) {
            if (p.equals("/pages/sso/reauth")) continue;
            assertEquals(404, status(mvc, "GET", p), "flag on + SSO, GET " + p);
        }
    }

    @Test
    void staleEntriesAreNotPublic_ws_openrosa_odmk_anonymousform() throws Exception {
        // Removed from PUBLIC_PATHS with the participant chain (#373): in either
        // mode these now need a login.
        for (Class<?> cfg : List.of(Off.class, On.class)) {
            MockMvc mvc = mvc(cfg);
            for (String p : List.of("/ws/studies", "/rest2/openrosa/S_X/formList", "/pages/odmk/studies",
                    "/pages/openrosa/S_X/submission", "/pages/api/v1/anonymousform/x", "/pages/accounts/x")) {
                int s = status(mvc, "GET", p);
                assertTrue(s == 401 || s == 404, cfg.getSimpleName() + " " + p + " -> " + s);
                assertTrue(s != 200, cfg.getSimpleName() + " " + p);
            }
        }
    }

    private static MockMvc mvc(Class<?> config) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(config);
        ctx.refresh();
        return MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", FilterChainProxy.class))
                .build();
    }

    private static MockHttpServletRequestBuilder anon(String method, String path) {
        return "POST".equals(method) ? post(path) : get(path);
    }

    private static int status(MockMvc mvc, String method, String path) throws Exception {
        return mvc.perform(anon(method, path)).andReturn().getResponse().getStatus();
    }

    @Test
    void flagOff_everyListedPathStillReachesTheApplication() throws Exception {
        MockMvc mvc = mvc(Off.class);
        for (String p : DENIED) {
            assertEquals(200, status(mvc, "GET", p), "flag off, GET " + p);
            assertEquals(200, status(mvc, "POST", p), "flag off, POST " + p);
        }
        for (String p : STILL_OPEN) {
            assertEquals(200, status(mvc, "GET", p), "flag off, GET " + p);
        }
    }

    @Test
    void flagOff_authenticatedOnlyPathsStillRequireLogin() throws Exception {
        MockMvc mvc = mvc(Off.class);
        assertEquals(401, status(mvc, "GET", "/pages/api/v1/me"));
        assertEquals(401, status(mvc, "GET", "/pages/api/v1/event-crfs/5/oct-upload"));
    }

    @Test
    void flagOn_everyListedPathIsAnswered404ForAnonymous() throws Exception {
        MockMvc mvc = mvc(On.class);
        for (String p : DENIED) {
            assertEquals(404, status(mvc, "GET", p), "flag on, GET " + p);
            assertEquals(404, status(mvc, "POST", p), "flag on, POST " + p);
        }
    }

    @Test
    void flagOn_authorizationRulesAloneDenyTheSamePaths() throws Exception {
        MockMvc mvc = mvc(OnRulesOnly.class);
        for (String p : DENIED) {
            int get = status(mvc, "GET", p);
            int post = status(mvc, "POST", p);
            assertTrue(get == 401 || get == 403, "flag on (rules only), GET " + p + " -> " + get);
            assertTrue(post == 401 || post == 403, "flag on (rules only), POST " + p + " -> " + post);
        }
    }

    @Test
    void flagOn_healthcheckAndLoginSurfaceStayOpen() throws Exception {
        for (Class<?> cfg : List.of(On.class, OnRulesOnly.class)) {
            MockMvc mvc = mvc(cfg);
            for (String p : STILL_OPEN) {
                assertEquals(200, status(mvc, "GET", p), cfg.getSimpleName() + ", GET " + p);
            }
            assertEquals(401, status(mvc, "GET", "/pages/api/v1/me"), cfg.getSimpleName());
        }
    }

    @Test
    void flagOn_nearMissPathsAreNotSwallowed() throws Exception {
        MockMvc mvc = mvc(On.class);
        // The deny list is prefix-exact: a sibling is not caught by it.
        assertEquals(200, status(mvc, "GET", "/pages/api/v1/contact"));
        assertEquals(401, status(mvc, "GET", "/pages/api/v1/publicity"));
    }
}
