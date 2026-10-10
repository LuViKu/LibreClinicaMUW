package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Where {@link SecurityConfig#RATE_LIMIT_ANCHOR} puts a filter in the chain.
 * Security 6 placed the public-upload rate limiter and the internet-facing 404
 * filter "before ChannelProcessingFilter"; Security 7 has no such filter, and
 * the anchor was moved to the next one in the order. A filter added at the
 * anchor must still run after the encode-URL filter and ahead of everything
 * that reads or builds the security context, so that a blocked or throttled
 * request is answered before any session or controller is touched.
 */
class SecurityConfigFilterOrderTest {

    /** Stand-in for the filters SecurityConfig anchors at the rate-limit position. */
    static class Marker extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, java.io.IOException {
            chain.doFilter(request, response);
        }
    }

    @Configuration
    @EnableWebSecurity
    static class Chain {
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(SecurityConfig.authorization(false, false))
                    .addFilterBefore(new Marker(), SecurityConfig.RATE_LIMIT_ANCHOR)
                    .logout(l -> l.logoutUrl("/j_spring_security_logout"))
                    .formLogin(f -> { })
                    .build();
        }
    }

    private static List<Class<?>> filterClasses() {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(Chain.class);
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
    void theRateLimitPositionIsAfterEncodeUrlAndAheadOfEverythingThatTouchesTheSession() {
        List<Class<?>> chain = filterClasses();
        assertBefore(chain, DisableEncodeUrlFilter.class, Marker.class);
        assertBefore(chain, Marker.class, WebAsyncManagerIntegrationFilter.class);
        assertBefore(chain, Marker.class, SecurityContextHolderFilter.class);
        assertBefore(chain, Marker.class, HeaderWriterFilter.class);
        assertBefore(chain, Marker.class, LogoutFilter.class);
        assertBefore(chain, Marker.class, UsernamePasswordAuthenticationFilter.class);
    }
}
