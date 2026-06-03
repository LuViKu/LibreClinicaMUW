package at.ac.meduniwien.ophthalmology.libreclinica.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.JitProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LookupOnlyProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.UserProvisioningStrategy;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaUsernamePasswordAuthenticationFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.SsoUserDetailsService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.TrustedProxyRequestHeaderAuthenticationFilter;

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
 * compat path catches). Pass explicit {@link AntPathRequestMatcher} instances
 * via {@link #antPaths(String...)} to bypass that check.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
            @Qualifier("openClinicaLogoutHandler") LogoutSuccessHandler logoutSuccessHandler,
            // Phase D.3 (DR-014): SSO pre-auth wiring. The filter is
            // only attached when libreclinica.sso.enabled=true;
            // otherwise the auth flow is identical to D.2 closure.
            SsoProperties ssoProperties,
            UserAccountDAO userAccountDao,
            // Phase D.5 (DR-014): shared audit-write hook for both
            // local-password and SSO pre-auth paths.
            LoginAuditService loginAuditService) throws Exception {

        http
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
            .csrf(csrf -> csrf.disable())
            .anonymous(anon -> {})
            .sessionManagement(sm -> sm.sessionAuthenticationStrategy(sas))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(antPaths(
                        "/pages/login/login",
                        "/SystemStatus",
                        "/RequestPassword",
                        "/RequestAccount",
                        "/Contact",
                        "/includes/**",
                        "/images/**",
                        "/help/**",
                        "/ws/**",
                        "/rest2/openrosa/**",
                        "/pages/odmk/**",
                        "/pages/openrosa/**",
                        "/pages/accounts/**",
                        "/pages/itemdata/**",
                        "/pages/auth/api/v1/studies/**",
                        "/pages/odmss/**",
                        "/pages/healthcheck/**",
                        "/pages/api/v1/anonymousform/**",
                        "/pages/api/v2/anonymousform/**",
                        "/pages/api/v1/editform/**",
                        "/pages/auth/api/v1/discrepancynote/**",
                        "/pages/auth/api/v1/forms/migrate/**",
                        "/pages/api/v1/forms/migrate/**",
                        "/pages/auth/api/**",
                        "/pages/auth/api/v1/system/**",
                        // Phase C.15 (2026-05-30): unauthenticated probes for
                        // k8s/load-balancer liveness + info. /actuator/health/*
                        // sub-paths show details only `when-authorized` per
                        // application.yml — anonymous probes see status only.
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
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
                        // controllers it documents. Both prefixes are
                        // permitted — the /v3/api-docs/* paths return 404
                        // now but the permit costs nothing and avoids a
                        // future-Self surprise.
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
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
                        "/app/**"
                )).permitAll()
                .anyRequest().hasRole("USER")
            )
            .addFilterAt(myFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAt(concurrencyFilter, ConcurrentSessionFilter.class)
            .logout(logout -> logout
                .logoutUrl("/j_spring_security_logout")
                .logoutSuccessHandler(logoutSuccessHandler)
            );

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

    @SuppressWarnings("deprecation")
    private static RequestMatcher[] antPaths(String... patterns) {
        RequestMatcher[] matchers = new RequestMatcher[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            matchers[i] = new AntPathRequestMatcher(patterns[i]);
        }
        return matchers;
    }
}
