package at.ac.meduniwien.ophthalmology.libreclinica.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.InternetFacingPathBlockFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.SpaLoginFailureHandler;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.LinkedHashMap;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.JitProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LookupOnlyProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.UserProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaSecurityContextLogoutHandler;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaUsernamePasswordAuthenticationFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.SsoUserDetailsService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.TrustedProxyRequestHeaderAuthenticationFilter;
import org.springframework.security.web.transport.HttpsRedirectFilter;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase C.14 cliff (2026-05-30): Java replacement for the
 * {@code <security:http>} block in {@code applicationContext-security.xml}.
 * The {@code SecurityFilterChain} @Bean is what Spring Boot 3.5's
 * {@code SecurityFilterAutoConfiguration} expects — Boot auto-registers
 * a {@code DelegatingFilterProxy} named {@code springSecurityFilterChain}
 * for it, replacing the web.xml entry.
 * <p>
 * Preserves Phase B.4 cliff semantics: explicit
 * {@code SecurityContextRepository} so the custom
 * {@link OpenClinicaUsernamePasswordAuthenticationFilter} saves the
 * {@code SecurityContext} into the SAME repository the
 * {@code SecurityContextHolderFilter} reads back. {@code myFilter} stays
 * at the {@code UsernamePasswordAuthenticationFilter} position;
 * {@code concurrencyFilter} stays at the {@code ConcurrentSessionFilter}
 * position — matches the XML's {@code <security:custom-filter position="FORM_LOGIN_FILTER" />}
 * and {@code position="CONCURRENT_SESSION_FILTER" />}.
 * <p>
 * <strong>Critical:</strong> {@code requestMatchers(String...)} in Spring
 * Security 6.4 calls {@code AbstractRequestMatcherRegistry.isDispatcherServlet}
 * which does {@code Class.forName("javax.servlet.Filter")} — on a
 * jakarta-only classpath the class doesn't exist and the JVM throws
 * {@code NoClassDefFoundError} (not the {@code ClassNotFoundException} the
 * compat path catches). Security 7 dropped that check and the Ant matcher; the
 * rules still pass explicit {@link PathPatternRequestMatcher} instances
 * via {@link #pathPatterns(String...)}, so they never depend on the MVC lookup.
 */
@Configuration
@EnableWebSecurity
@EnableScheduling
public class SecurityConfig {

    /**
     * Where the rate limiter and the internet-facing block sit in the chain.
     * Security 6 anchored them before {@code ChannelProcessingFilter}, which
     * Security 7 removed together with channel security. Its slot in the filter
     * order is now empty and {@link HttpsRedirectFilter} is the next filter after
     * it, so "before {@code HttpsRedirectFilter}" is the same position: after
     * the encode-URL and eager-session filters, ahead of the security context,
     * logout, pre-authentication and form login.
     */
    static final Class<HttpsRedirectFilter> RATE_LIMIT_ANCHOR = HttpsRedirectFilter.class;

    /**
     * Paths any caller may reach without a session. A live handler behind a
     * path is not a reason to list it here: the handler must be one that is
     * meant to answer anonymous callers (login, public portals, device
     * uploaders with their own tokens, probes). {@code SecurityConfigPublicPathsTest}
     * pins the ones that must stay closed.
     */
    static final String[] PUBLIC_PATHS = {
            "/pages/login/login",
            "/SystemStatus",
            "/RequestAccount",
            "/Contact",
            "/includes/**",
            "/images/**",
            "/pages/auth/api/v1/studies/**",
            // Phase E.8 Slice L2 (2026-06-20): SPA replacement
            // for the legacy /pages/Contact JSP. Unauthenticated
            // by design — same audience as the legacy form.
            // Rate-limit lives at the reverse proxy.
            "/pages/api/v1/contact",
            // Public OCT upload portal — see oct-upload-portal plan.
            // Trust-the-reverse-proxy exposure: must NOT be exposed
            // to public internet. Token CSRF is disabled for this
            // chain (see above); the page posts same-origin, so
            // CrossSiteRequestFilter lets it through.
            "/pages/api/v1/public/oct-upload/**",
            // 2026-06-24 user-feedback round — public BCVA-entry
            // portal (mirrors OCT-upload posture). Same
            // trust-the-reverse-proxy gate; nurses don't have
            // accounts. See PublicBcvaEntryController.
            "/pages/api/v1/public/bcva-entry/**",
            // DR-025 — public Remidio image-upload portal (no
            // account; reverse-proxy gated like the OCT/BCVA ones).
            // Lands in ingest_item(source_kind='upload').
            "/pages/api/v1/public/image-upload/**",
            // DR-029 — the combined upload page (OCT, DICOM, JPEG/PNG);
            // same posture as the two pages it replaces, which stay
            // mounted for one release.
            "/pages/api/v1/public/upload/**",
            // DR-025 — internal DICOM ingest handoff from the
            // dicom-scp sidecar. Trust-the-reverse-proxy exposure +
            // a shared-secret X-MUW-Dicom-Token gate in
            // DicomIngestApiController; never expose publicly.
            "/pages/api/v1/internal/dicom-ingest/**",
            // DR-025 — Modality Worklist source for the sidecar
            // (same shared-secret gate; the Lumo pulls scheduled
            // visits via the sidecar's C-FIND).
            "/pages/api/v1/internal/dicom-worklist/**",
            // 2026-09-23 — the Optomed Client's worklist file for a
            // USB-docked Lumo (the camera cannot join the Enterprise
            // WLAN). Reached from a clinic PC THROUGH the proxy, so
            // unlike the two internal paths above it is not refused
            // at the edge; it earns that by carrying a placeholder
            // date of birth, its own X-MUW-Optomed-Token, and being
            // off (404) unless core.optomed.worklist.enabled=true.
            // See OptomedWorklistApiController.
            "/pages/api/v1/device/optomed/**",
            // DR-033 — heartbeats from the uploaders on the
            // acquisition PCs (Export Watcher, Optomed Bridge).
            // No patient data; bounded fields, 16 KB body, at most
            // 50 programs, rows keyed by a random instance id. Off
            // (404) with core.uploaderHealth.heartbeat.enabled=false.
            // See UploaderHeartbeatApiController.
            "/pages/api/v1/device/uploader/**",
            "/pages/auth/api/v1/discrepancynote/**",
            "/pages/auth/api/v1/forms/migrate/**",
            "/pages/auth/api/**",
            "/pages/auth/api/v1/system/**",
            // Phase C.15 (2026-05-30): unauthenticated probes for
            // k8s/load-balancer liveness + info. /actuator/health/*
            // sub-paths show details only `when-authorized` per
            // application.yml — anonymous probes see status only.
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // Phase E-hardening B1 (2026-06-10): Prometheus scrape
            // endpoint. The institutional reverse-proxy is the
            // canonical gate preventing public exposure — the
            // permitAll here lets the metrics endpoint be readable
            // from inside the deployment network (Prometheus +
            // node-exporter sidecar) without requiring a session.
            "/actuator/prometheus",
            // Phase D.10 (DR-014): e-signature re-auth
            // scaffolding endpoint. Always permits — the
            // reverse proxy may strip the existing session
            // before re-challenge, so the 302 emitter
            // doesn't need a LibreClinica session. The
            // production-readiness of this path is gated
            // by libreclinica.sso.reauth.enabled (default
            // false); the endpoint itself is always
            // wired so a future Sign Subject controller
            // can invoke it when legal/regulatory
            // ratifies proxy-mediated §11.50 e-signatures.
            // Resolved on the pages dispatcher because
            // Boot's root @ComponentScan does not include
            // the controller package.
            "/pages/sso/reauth",
            // Phase E.5 B3: springdoc-openapi spec + Swagger
            // UI. Contains the API surface description
            // (paths + DTO schemas), no clinical data.
            // Public access lets the SPA's
            // `codegen:openapi` step + ops sanity checks
            // run without an auth session.
            //
            // Phase E.5 follow-up (2026-06-01): springdoc beans
            // were relocated into the `pages` DispatcherServlet
            // child context (the only place its
            // RequestMappingHandlerMapping sees the
            // /api/v1/** @RestController family). With
            // springdoc.api-docs.path = /pages/v3/api-docs the
            // OpenApiResource registers at that prefix so its
            // URLs flow through the same dispatcher as the
            // controllers it documents. Only the /pages
            // prefix is served; the root /v3/api-docs and
            // /swagger-ui paths answer 404 and are not listed.
            "/pages/v3/api-docs",
            "/pages/v3/api-docs/**",
            "/pages/v3/api-docs.yaml",
            "/pages/swagger-ui.html",
            "/pages/swagger-ui/**",
            // Phase E.5 (2026-06-03): Vue 3 SPA static bundle.
            // The SPA's index.html, /app/assets/* (JS + CSS +
            // source maps), favicon.svg and every client-side
            // route (/app/login, /app/first-login, /app/home,
            // /app/subjects, ...) all serve the same index.html
            // and the router handles routing in-browser.
            //
            // Anonymous access is intentional + safe:
            //   - the bundle contains zero PHI and zero secrets;
            //     only compiled Vue source code that references
            //     API URLs that are independently auth-gated;
            //   - the SPA's auth.bootstrap() probes
            //     GET /pages/api/v1/me on load — that endpoint
            //     stays behind hasRole("USER"), returns 401 for
            //     anonymous, and the router-guard then routes
            //     the SPA to /app/login client-side;
            //   - API endpoint paths the bundle exposes are also
            //     discoverable from springdoc-openapi at
            //     /pages/v3/api-docs (already permitAll above),
            //     so opening /app/** doesn't widen the
            //     enumeration surface;
            //   - source maps reveal source structure but no
            //     secrets; matches the standard SPA-on-static-
            //     hosting threat model (S3 + CloudFront + API
            //     behind auth).
            //
            // Without this rule, /app/login itself was behind
            // hasRole("USER") and unauthenticated users got
            // 302'd to the legacy /pages/login/login JSP before
            // the SPA's LoginView ever loaded — the SPA's own
            // login screen was unreachable in production builds
            // and only visible via the Vite dev server.
            "/app/**",
            // Phase E hardening (A2): GlobalErrorServlet is
            // mapped at /error in web.xml and is the target of
            // every <error-page> entry. Without an explicit
            // permitAll, the catch-all hasRole("USER") below
            // 302s anonymous failure paths to the login page —
            // hiding the German error JSP from unauthenticated
            // callers and breaking the SPA's JSON-500 contract
            // for the un-logged-in case. Safe to expose
            // anonymously: the servlet renders ONLY the
            // Tomcat-supplied javax.servlet.error.* request
            // attributes (status, exception class+message,
            // request URI), never DB contents nor session
            // state.
            "/error"
    };

    /**
     * Paths that must not be reachable on an internet-facing deployment
     * ({@code libreclinica.deployment.internet-facing=true}). They are answered
     * 404 by {@link InternetFacingPathBlockFilter} (so existence is not
     * revealed) and, as defence in depth, denied by the authorization rules.
     * {@code /actuator/health}, {@code /pages/api/v1/contact}, {@code /app/**},
     * the login page and {@code /error} stay open.
     * <ul>
     *   <li>the account-less upload / entry portals and the device and sidecar
     *       APIs ({@code /pages/api/v1/public|internal|device});</li>
     *   <li>information-leaking operational endpoints: actuator info and
     *       prometheus, springdoc and swagger (pages and bare twins);</li>
     *   <li>the heritage API-key REST API under {@code /pages/auth}, closed
     *       (410) by default in {@code libreclinica.legacy.closedPaths} but
     *       reopenable by that setting, so the public-path entries behind it
     *       are denied here as well; and the anonymous {@code /SystemStatus}
     *       page.</li>
     * </ul>
     * {@code /pages/sso/reauth} is added by {@link #internetFacingDeniedPaths}
     * only when SSO is off; with SSO on the proxy-mediated re-authentication
     * flow needs it.
     */
    public static final String[] INTERNET_FACING_DENIED_PATHS = {
            "/pages/api/v1/public/**",
            "/pages/api/v1/internal/**",
            "/pages/api/v1/device/**",
            "/actuator/info",
            "/actuator/info/**",
            "/actuator/prometheus",
            "/actuator/prometheus/**",
            "/pages/v3/api-docs*",
            "/pages/v3/api-docs/**",
            "/pages/swagger-ui*",
            "/pages/swagger-ui/**",
            "/v3/api-docs*",
            "/v3/api-docs/**",
            "/swagger-ui*",
            "/swagger-ui/**",
            "/pages/auth/**",
            "/SystemStatus",
            "/SystemStatus/**",
    };

    /** The deny list for a deployment: the fixed list plus SSO re-auth when SSO is off. */
    public static String[] internetFacingDeniedPaths(boolean ssoEnabled) {
        if (ssoEnabled) {
            return INTERNET_FACING_DENIED_PATHS;
        }
        String[] all = java.util.Arrays.copyOf(INTERNET_FACING_DENIED_PATHS,
                INTERNET_FACING_DENIED_PATHS.length + 1);
        all[all.length - 1] = "/pages/sso/reauth";
        return all;
    }

    /**
     * URL authorization rules. When {@code internetFacing} the deny rule is
     * registered FIRST so it wins over the {@code permitAll} list that follows.
     */
    static Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
            authorization(boolean internetFacing, boolean ssoEnabled) {
        return authorization(internetFacing, ssoEnabled, false);
    }

    /**
     * Paths that take a file in without a session. Closed whenever
     * {@code libreclinica.ingest.deidentification.required} is on, whether or
     * not the deployment is internet-facing: nothing unverified gets in. The
     * DICOM C-STORE hand-off is the sidecar's way of filing a camera's file
     * unchecked.
     */
    public static final String[] DEIDENTIFICATION_CLOSED_PATHS = {
            "/pages/api/v1/public/upload/**",
            "/pages/api/v1/public/oct-upload/**",
            "/pages/api/v1/public/image-upload/**",
            "/pages/api/v1/internal/dicom-ingest",
            "/pages/api/v1/internal/dicom-ingest/**",
    };

    static Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
            authorization(boolean internetFacing, boolean ssoEnabled, boolean deidRequired) {
        return auth -> {
            if (internetFacing) {
                auth.requestMatchers(pathPatterns(internetFacingDeniedPaths(ssoEnabled))).denyAll();
            }
            if (deidRequired) {
                auth.requestMatchers(pathPatterns(DEIDENTIFICATION_CLOSED_PATHS)).denyAll();
            }
            auth.requestMatchers(pathPatterns(PUBLIC_PATHS)).permitAll()
                .anyRequest().hasRole("USER");
        };
    }

    /**
     * Phase D.3 (DR-014): SSO config bean with a stable ID
     * ({@code ssoProperties}) so the legacy
     * {@code applicationContext-security.xml} can {@code <ref bean=…/>}
     * it for the myFilter wiring. Registered explicitly (rather
     * than via {@code @EnableConfigurationProperties}) because the
     * latter generates a long auto-name that XML refs can't resolve.
     */
    @Bean
    @ConfigurationProperties("libreclinica.sso")
    public SsoProperties ssoProperties() {
        return new SsoProperties();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("securityContextRepository") SecurityContextRepository securityContextRepository,
            @Qualifier("authenticationProcessingFilterEntryPoint") AuthenticationEntryPoint authenticationEntryPoint,
            @Qualifier("myFilter") OpenClinicaUsernamePasswordAuthenticationFilter myFilter,
            @Qualifier("concurrencyFilter") ConcurrentSessionFilter concurrencyFilter,
            @Qualifier("sas") SessionAuthenticationStrategy sas,
            @Qualifier("openClinicaLogoutHandler") OpenClinicaSecurityContextLogoutHandler logoutHandler,
            // Phase D.3 (DR-014): SSO pre-auth wiring. The filter is
            // only attached when libreclinica.sso.enabled=true;
            // otherwise the auth flow is identical to D.2 closure.
            SsoProperties ssoProperties,
            UserAccountDAO userAccountDao,
            // Phase D.5 (DR-014): shared audit-write hook for both
            // local-password and SSO pre-auth paths.
            LoginAuditService loginAuditService,
            // Wave 1B (2026-06-18): hand-rolled token-bucket on the
            // public OCT-upload portal. The filter no-ops for every
            // path that doesn't match its guarded prefix; cost is one
            // request URI startsWith for the rest of the chain.
            PublicOctUploadRateLimitFilter publicOctUploadRateLimitFilter,
            // Second, internet-facing deployment: closes the portals, device
            // APIs and operational endpoints. Off for the internal deployment.
            @Value("${libreclinica.deployment.internet-facing:false}") boolean internetFacing,
            // Every upload must be verified as de-identified: closes the
            // account-less upload routes and the DICOM C-STORE hand-off
            // whether or not the deployment is internet-facing.
            @Value("${libreclinica.ingest.deidentification.required:${libreclinica.deployment.internet-facing:false}}")
                    boolean deidRequired,
            @Qualifier("failureHandler") SpaLoginFailureHandler failureHandler) throws Exception {
        // One failure for every cause when internet-facing (see the handler).
        failureHandler.setInternetFacing(internetFacing);

        // Phase E.6 (2026-06-03) — SPA-vs-legacy entry-point split.
        // The legacy form-login entry point at /pages/login/login emits
        // a 302 redirect for unauthenticated requests, which breaks the
        // SPA: on a full page reload, auth.bootstrap() calls
        // /pages/api/v1/me, follows the redirect to /pages/login/login,
        // gets HTML, can't parse it, falls through to anonymous and
        // bounces to /app/login. The fix is to return a clean 401 for
        // /pages/api/** (SPA channel) while keeping the 302 redirect
        // for every other unauthenticated request (legacy JSP channel).
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(pathPattern("/pages/api/**"),
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        DelegatingAuthenticationEntryPoint splitEntryPoint =
                new DelegatingAuthenticationEntryPoint(entryPoints);
        splitEntryPoint.setDefaultEntryPoint(authenticationEntryPoint);

        http
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .exceptionHandling(eh -> eh.authenticationEntryPoint(splitEntryPoint))
            // Token CSRF stays off: several hundred heritage JSP forms post
            // without a token. The CSRF defence is token-free instead, and
            // applies to the whole application, not only this chain:
            //   1. CrossSiteRequestFilter (ServletInfraConfig, ahead of this
            //      chain) refuses POST/PUT/PATCH/DELETE that a browser sends
            //      from another site — Sec-Fetch-Site must be same-origin or
            //      none; without Fetch Metadata an Origin header must match
            //      the request's own origin. Clients sending neither header
            //      (device uploaders, DICOM receiver, API-key callers) pass.
            //   2. The session cookie is SameSite=Lax (Tomcat CookieProcessor
            //      in web/src/main/webapp/META-INF/context.xml).
            //   3. Handlers that change data accept POST only, so a Lax cookie
            //      riding a cross-site GET navigation cannot trigger them.
            .csrf(csrf -> csrf.disable())
            .anonymous(_ -> {})
            .sessionManagement(sm -> sm.sessionAuthenticationStrategy(sas))
            .authorizeHttpRequests(authorization(internetFacing, ssoProperties.isEnabled(), deidRequired))
            .addFilterBefore(publicOctUploadRateLimitFilter, RATE_LIMIT_ANCHOR)
            .addFilterAt(myFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAt(concurrencyFilter, ConcurrentSessionFilter.class)
            .logout(logout -> logout
                .logoutUrl("/j_spring_security_logout")
                // Runs ahead of the filter's own handler, which invalidates
                // the session: the logout audit row is written once.
                .addLogoutHandler(logoutHandler)
                .logoutSuccessHandler(logoutHandler)
            );

        // Internet-facing deployment: answer 404 for the portals, device APIs
        // and operational endpoints before the rate limiter or any controller
        // sees the request. Registered after the rate-limit filter's anchor but
        // placed before it so it runs first. The authorization rules deny the
        // same paths as a second layer.
        if (internetFacing) {
            http.addFilterBefore(
                    new InternetFacingPathBlockFilter(internetFacingDeniedPaths(ssoProperties.isEnabled())),
                    PublicOctUploadRateLimitFilter.class);
        } else if (deidRequired) {
            http.addFilterBefore(new InternetFacingPathBlockFilter(DEIDENTIFICATION_CLOSED_PATHS),
                    PublicOctUploadRateLimitFilter.class);
        }

        // Phase D.3 (DR-014): institution-agnostic SSO pre-auth.
        // Wired AHEAD of myFilter (the local username/password filter)
        // so a valid pre-auth header short-circuits the local path.
        // CIDR allowlist refuses pre-auth claims from untrusted
        // upstream IPs (header-spoofing defence).
        if (ssoProperties.isEnabled()) {
            TrustedProxyRequestHeaderAuthenticationFilter preAuthFilter =
                    new TrustedProxyRequestHeaderAuthenticationFilter(
                            ssoProperties.getTrustedProxy().getAllowedCidrs());
            preAuthFilter.setPrincipalRequestHeader(
                    ssoProperties.getHeader().getPrincipal());
            // exceptionIfHeaderMissing=false → header-absent requests
            // fall through to the local username/password path; only
            // a present-AND-trusted header attempts pre-auth.
            preAuthFilter.setExceptionIfHeaderMissing(false);

            // Phase D.4 (DR-014): select the provisioning strategy
            // from configuration. LOOKUP_ONLY (default) rejects
            // unknown principals; JIT scaffold currently behaves
            // like LOOKUP_ONLY pending the row-creation impl per
            // its class Javadoc.
            UserProvisioningStrategy strategy;
            switch (ssoProperties.getProvisioning().getStrategy()) {
                case JIT:
                    strategy = new JitProvisioningStrategy(userAccountDao);
                    break;
                case LOOKUP_ONLY:
                default:
                    strategy = new LookupOnlyProvisioningStrategy(userAccountDao);
                    break;
            }
            SsoUserDetailsService ssoUserDetailsService =
                    new SsoUserDetailsService(strategy, ssoProperties, loginAuditService);
            PreAuthenticatedAuthenticationProvider provider =
                    new PreAuthenticatedAuthenticationProvider();
            provider.setPreAuthenticatedUserDetailsService(ssoUserDetailsService);
            AuthenticationManager preAuthMgr = new ProviderManager(provider);
            preAuthFilter.setAuthenticationManager(preAuthMgr);

            http.addFilterBefore(preAuthFilter,
                    OpenClinicaUsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /**
     * Matcher for one pattern. Spring 7 path patterns are strict about the
     * trailing slash ({@code /Contact/} does not match {@code /Contact}), as the
     * Ant matcher this replaces was; a pattern meant to cover the slash form
     * says so ({@code /x/**}). Applied to the request path below the context
     * path with the container's path parameters ({@code ;jsessionid=}) ignored.
     * Used by the authorization rules, the entry-point split and
     * {@link InternetFacingPathBlockFilter}.
     */
    public static RequestMatcher pathPattern(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    static RequestMatcher[] pathPatterns(String... patterns) {
        RequestMatcher[] matchers = new RequestMatcher[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            matchers[i] = pathPattern(patterns[i]);
        }
        return matchers;
    }
}
