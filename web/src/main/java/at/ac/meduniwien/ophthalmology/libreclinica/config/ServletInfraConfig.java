package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;

import at.ac.meduniwien.ophthalmology.libreclinica.control.OCServletContextListener;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.OCServletFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.OCContextLoaderListener;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.ApiSecurityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.CrossSiteRequestFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.LocaleFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaUsernamePasswordAuthenticationFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.RequestIdFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyAliasServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletTelemetryFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;

/**
 * Phase C.14 cliff (2026-05-30): replaces the {@code <listener>}s and
 * non-security {@code <filter>}s from {@code web.xml} with Boot-managed
 * registration beans.
 * <p>
 * <strong>Listeners:</strong> {@link OCContextLoaderListener} (MDC + hostname
 * setup, now a pure {@code ServletContextListener}); {@link HttpSessionEventPublisher}
 * (Spring Security concurrent-session control); {@link OCServletContextListener}
 * (OC version + usage-stats startup). {@code RequestContextListener} is
 * retired — Boot's WebMvc autoconfig provides equivalent request-scoped
 * bean wiring.
 * <p>
 * <strong>Filters (preserve legacy chain order):</strong>
 * {@code requestIdFilter} → {@code legacyServletTelemetryFilter} →
 * {@code crossSiteRequestFilter} ({@link CrossSiteRequestFilter}) →
 * {@code encodingFilter} → {@code localeFilter} → {@code springSecurityFilterChain}
 * (auto-registered by Boot's {@code SecurityFilterAutoConfiguration} once
 * {@link SecurityConfig} provides the {@code SecurityFilterChain} bean) →
 * {@code hibernateFilter} ({@link OpenEntityManagerInViewFilter}) →
 * {@code logFilter} ({@link OCServletFilter}) →
 * {@code apiSecurityFilter} ({@link DelegatingFilterProxy} → {@code apiSecurityFilter}
 * bean, scoped to {@code /pages/auth/api/*}).
 * <p>
 * <strong>Opt-out registrations:</strong> Boot's
 * {@code ServletContextInitializerBeans} auto-creates a
 * {@code FilterRegistrationBean} (URL pattern {@code /*}, enabled) for every
 * {@link Filter} bean in the context. {@code myFilter}, {@code concurrencyFilter},
 * and {@code apiSecurityFilter} are XML-defined {@code Filter} beans consumed
 * via {@link SecurityConfig#securityFilterChain(...)} ({@code addFilterAt})
 * or via {@code DelegatingFilterProxy} above. Explicit
 * {@code FilterRegistrationBean.setEnabled(false)} entries below tell Boot
 * to skip the auto-registration for those beans.
 */
@Configuration
public class ServletInfraConfig {

    // --- Listeners ---

    @Bean
    public ServletListenerRegistrationBean<OCContextLoaderListener> ocContextLoaderListener() {
        return new ServletListenerRegistrationBean<>(new OCContextLoaderListener());
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    public ServletListenerRegistrationBean<OCServletContextListener> ocServletContextListener() {
        return new ServletListenerRegistrationBean<>(new OCServletContextListener());
    }

    // --- Filters ---

    /**
     * Phase E hardening A4 — first filter in the chain. Populates the
     * {@code reqId} MDC key + {@code X-Request-Id} response header before
     * any downstream filter logs. See {@link RequestIdFilter} JavaDoc for
     * the rationale on filter ordering + MDC hygiene.
     */
    /*
     * 2026-06-19 — every FilterRegistrationBean below explicitly sets
     * {@code setAsyncSupported(true)}. Without this, Tomcat marks the
     * resolved filter chain as not-async-supported as soon as any one
     * filter's registration leaves the value at its default; the
     * SseEmitter-backed {@code GET /pages/api/v1/retinal-jobs/{id}/status/stream}
     * endpoint then throws {@code IllegalStateException: Async support
     * must be enabled on a servlet and for all filters involved in
     * async request processing.}, the SPA's EventSource auto-retries
     * at ~1 Hz, and the libreclinica log fills with stack traces (the
     * SSE flood observed during the 2026-06-18 OCT-portal smoke).
     * Boot 3 used to default this to {@code true} on
     * {@link FilterRegistrationBean} — being explicit makes the
     * behaviour future-proof against further Boot defaults churn.
     */

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg =
                new FilterRegistrationBean<>(new RequestIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setAsyncSupported(true);
        return reg;
    }

    /**
     * Phase E.8 legacy-retirement (2026-06-20) — explicit bean for the
     * deprecation catalog. The catalog itself is annotated
     * {@code @Component}, but its package
     * ({@code …libreclinica.web.deprecation}) is not under either of
     * the application's two narrow {@code scanBasePackages} settings
     * ({@code .config} on the root context, {@code .controller} on the
     * pages child context). Without this explicit bean Boot startup
     * fails with "required a bean of type
     * LegacyServletDeprecationCatalog that could not be found" — see
     * the compose smoke-test failure on lc-develop tip 5bdefd25a.
     */
    @Bean
    public LegacyServletDeprecationCatalog legacyServletDeprecationCatalog() {
        return new LegacyServletDeprecationCatalog();
    }

    /**
     * Legacy-retirement gate (DR-018, plan R0.3/R0.4): logs every request
     * for a legacy screen as a {@code legacy-hit} line on the
     * {@code legacy-access} logger, and closes the screens listed in
     * {@code libreclinica.legacy.closedPaths} (410 Gone; a system
     * administrator gets a 307 to the {@code /legacy/} alias instead). See
     * {@link LegacyServletTelemetryFilter} for the behaviour and the
     * configuration.
     *
     * <p>Mapped on {@code /*} because the legacy servlets sit at the
     * context root ({@code /ListUserAccounts}), not under {@code /pages};
     * the filter recognises them from the servlet path, so every other
     * request costs a hash probe. {@code REQUEST} dispatches only: the
     * alias reaches a closed screen by an internal forward, which must not
     * be closed again.
     *
     * <p>Ordered just after {@link #requestIdFilter()} so the
     * {@code reqId} MDC value is already populated when this filter
     * logs the hit, and ahead of the security chain so that an
     * unauthenticated request for a closed screen gets the 410.
     */
    @Bean
    public FilterRegistrationBean<LegacyServletTelemetryFilter> legacyServletTelemetryFilter(
            LegacyServletDeprecationCatalog catalog,
            @Value("${libreclinica.legacy.closedPaths:}") String closedPaths,
            @Value("${libreclinica.deployment.internet-facing:false}") boolean internetFacing) {
        FilterRegistrationBean<LegacyServletTelemetryFilter> reg =
                new FilterRegistrationBean<>(new LegacyServletTelemetryFilter(
                        catalog, LegacyServletTelemetryFilter.parseClosedPaths(closedPaths), internetFacing));
        reg.addUrlPatterns("/*");
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.setAsyncSupported(true);
        return reg;
    }

    /**
     * DR-018 point 2: {@code /legacy/<path>} renders a legacy screen,
     * closed or not, for a system administrator and answers 404 to
     * everyone else; see {@link LegacyAliasServlet}. A servlet, so it
     * runs behind Spring Security, whose {@code anyRequest().hasRole("USER")}
     * already sends an unauthenticated request to the login page.
     */
    @Bean
    public ServletRegistrationBean<LegacyAliasServlet> legacyAliasServlet(
            LegacyServletDeprecationCatalog catalog) {
        ServletRegistrationBean<LegacyAliasServlet> reg =
                new ServletRegistrationBean<>(new LegacyAliasServlet(catalog), LegacyAliasServlet.URL_PATTERN);
        reg.setName("legacyAliasServlet");
        return reg;
    }

    /**
     * CSRF defence (token-free): refuses POST/PUT/PATCH/DELETE that a browser
     * sends from another site, judged by Fetch Metadata or, failing that, the
     * Origin header. See {@link CrossSiteRequestFilter} and the comment on
     * {@code csrf().disable()} in {@link SecurityConfig}.
     *
     * <p>Ordered after the request-id and telemetry filters (so a refusal is
     * logged with its {@code reqId}) and ahead of everything that could act on
     * the request, including the security chain. It reads headers only, so it
     * does not need the character-encoding filter to have run.
     */
    @Bean
    public FilterRegistrationBean<CrossSiteRequestFilter> crossSiteRequestFilter() {
        FilterRegistrationBean<CrossSiteRequestFilter> reg =
                new FilterRegistrationBean<>(new CrossSiteRequestFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<CharacterEncodingFilter> encodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding(StandardCharsets.UTF_8.name());
        filter.setForceEncoding(true);
        FilterRegistrationBean<CharacterEncodingFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        // A4 (2026-06-10): bumped from HIGHEST_PRECEDENCE to +5 so
        // requestIdFilter sits strictly ahead of every other filter.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<LocaleFilter> localeFilter() {
        FilterRegistrationBean<LocaleFilter> reg = new FilterRegistrationBean<>(new LocaleFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> hibernateFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> reg =
                new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OCServletFilter> logFilter() {
        FilterRegistrationBean<OCServletFilter> reg =
                new FilterRegistrationBean<>(new OCServletFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<Filter> apiSecurityFilterProxy() {
        DelegatingFilterProxy proxy = new DelegatingFilterProxy("apiSecurityFilter");
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(proxy);
        reg.addUrlPatterns("/pages/auth/api/*");
        reg.setName("apiSecurityFilterProxy");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        reg.setAsyncSupported(true);
        return reg;
    }

    // --- Opt-out registrations for XML-defined Filter beans ---

    @Bean
    public FilterRegistrationBean<OpenClinicaUsernamePasswordAuthenticationFilter>
            myFilterAutoRegOptOut(
                    @Qualifier("myFilter")
                    OpenClinicaUsernamePasswordAuthenticationFilter myFilter) {
        FilterRegistrationBean<OpenClinicaUsernamePasswordAuthenticationFilter> reg =
                new FilterRegistrationBean<>(myFilter);
        reg.setEnabled(false);
        // 2026-06-19 — even disabled, the registration metadata is
        // surfaced to Tomcat's filter-chain bookkeeping. Marking it
        // async-supported avoids poisoning request.isAsyncSupported()
        // on any chain that happens to include the bean. See class
        // Javadoc's SSE fix note for full context.
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ConcurrentSessionFilter> concurrencyFilterAutoRegOptOut(
            @Qualifier("concurrencyFilter") ConcurrentSessionFilter concurrencyFilter) {
        FilterRegistrationBean<ConcurrentSessionFilter> reg =
                new FilterRegistrationBean<>(concurrencyFilter);
        reg.setEnabled(false);
        // 2026-06-19 — even disabled, the registration metadata is
        // surfaced to Tomcat's filter-chain bookkeeping. Marking it
        // async-supported avoids poisoning request.isAsyncSupported()
        // on any chain that happens to include the bean. See class
        // Javadoc's SSE fix note for full context.
        reg.setAsyncSupported(true);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ApiSecurityFilter> apiSecurityFilterAutoRegOptOut(
            @Qualifier("apiSecurityFilter") ApiSecurityFilter apiSecurityFilter) {
        FilterRegistrationBean<ApiSecurityFilter> reg =
                new FilterRegistrationBean<>(apiSecurityFilter);
        reg.setEnabled(false);
        // 2026-06-19 — even disabled, the registration metadata is
        // surfaced to Tomcat's filter-chain bookkeeping. Marking it
        // async-supported avoids poisoning request.isAsyncSupported()
        // on any chain that happens to include the bean. See class
        // Javadoc's SSE fix note for full context.
        reg.setAsyncSupported(true);
        return reg;
    }

    /**
     * Explicit bean factory for the public OCT-upload rate-limit
     * filter.
     *
     * <p>{@link at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter}
     * carries {@code @Component} but lives in the {@code .web}
     * package, which is NOT covered by the root
     * {@link LibreClinicaApplication @SpringBootApplication}'s
     * {@code scanBasePackages = ".config"} scope. Without this
     * factory, Spring fails to start with an
     * {@code UnsatisfiedDependencyException} when
     * {@link #publicOctUploadRateLimitFilterAutoRegOptOut(at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter)}
     * and {@link SecurityConfig#securityFilterChain(...)} try to
     * autowire it. Hotfix for the PR #211 merge regression
     * (2026-06-18 boot failure observed against the merged tip).
     */
    @Bean
    public at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter
            publicOctUploadRateLimitFilter(
                    // Throttles only on the internet-facing deployment; on the
                    // internal one it passes everything through (see the filter's
                    // `enabled` field for why).
                    @Value("${libreclinica.deployment.internet-facing:false}") boolean internetFacing) {
        return new at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter(internetFacing);
    }

    /**
     * Wave 1B (2026-06-18): the public OCT-upload rate-limit filter is
     * mounted inside the {@code SecurityFilterChain} via
     * {@link SecurityConfig#securityFilterChain(...)}'s
     * {@code .addFilterBefore(...)}. Boot would otherwise auto-register
     * it as a standalone servlet filter at {@code /*} and the token
     * decrement would happen twice per request.
     */
    @Bean
    public FilterRegistrationBean<at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter>
            publicOctUploadRateLimitFilterAutoRegOptOut(
                    at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter filter) {
        FilterRegistrationBean<at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter>
                reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        // 2026-06-19 — even disabled, the registration metadata is
        // surfaced to Tomcat's filter-chain bookkeeping. Marking it
        // async-supported avoids poisoning request.isAsyncSupported()
        // on any chain that happens to include the bean. See class
        // Javadoc's SSE fix note for full context.
        reg.setAsyncSupported(true);
        return reg;
    }
}
