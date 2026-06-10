package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
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
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.LocaleFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaUsernamePasswordAuthenticationFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.RequestIdFilter;
import jakarta.servlet.Filter;

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
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg =
                new FilterRegistrationBean<>(new RequestIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
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
        return reg;
    }

    @Bean
    public FilterRegistrationBean<LocaleFilter> localeFilter() {
        FilterRegistrationBean<LocaleFilter> reg = new FilterRegistrationBean<>(new LocaleFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> hibernateFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> reg =
                new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OCServletFilter> logFilter() {
        FilterRegistrationBean<OCServletFilter> reg =
                new FilterRegistrationBean<>(new OCServletFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<Filter> apiSecurityFilterProxy() {
        DelegatingFilterProxy proxy = new DelegatingFilterProxy("apiSecurityFilter");
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(proxy);
        reg.addUrlPatterns("/pages/auth/api/*");
        reg.setName("apiSecurityFilterProxy");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
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
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ConcurrentSessionFilter> concurrencyFilterAutoRegOptOut(
            @Qualifier("concurrencyFilter") ConcurrentSessionFilter concurrencyFilter) {
        FilterRegistrationBean<ConcurrentSessionFilter> reg =
                new FilterRegistrationBean<>(concurrencyFilter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ApiSecurityFilter> apiSecurityFilterAutoRegOptOut(
            @Qualifier("apiSecurityFilter") ApiSecurityFilter apiSecurityFilter) {
        FilterRegistrationBean<ApiSecurityFilter> reg =
                new FilterRegistrationBean<>(apiSecurityFilter);
        reg.setEnabled(false);
        return reg;
    }
}
