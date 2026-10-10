package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.util.ServletRequestPathUtils;

import at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;

/**
 * {@code /%70ages/...} answers 400, not 500. Tomcat decodes the letter before it
 * maps the request, so the servlet path is {@code /pages} while the raw URI
 * still says {@code %70ages}; Spring 7 cannot split the context path off such a
 * request and throws, in the first filter of the security chain.
 * {@code SecurityConfigMatcherEquivalenceTest} pins that no allow or deny decision
 * moves; this class pins the status.
 */
class UnparseablePathFilterTest {

    private static final String CONTEXT = "/LibreClinica";

    @Configuration
    @EnableWebSecurity
    static class Chain {
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .formLogin(f -> { })
                    .build();
        }
    }

    private static AnnotationConfigWebApplicationContext ctx;
    private static Filter securityChain;

    @BeforeAll
    static void startContext() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(Chain.class);
        ctx.refresh();
        securityChain = ctx.getBean("springSecurityFilterChain", Filter.class);
    }

    @AfterAll
    static void stopContext() {
        ctx.close();
    }

    /** The request as Tomcat hands it to the pages dispatcher. */
    private static MockHttpServletRequest pagesRequest(String rawPath, String decodedPath) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", CONTEXT + rawPath);
        r.setContextPath(CONTEXT);
        r.setServletPath("/pages");
        r.setPathInfo(decodedPath.substring("/pages".length()));
        return r;
    }

    private static final class Reached extends HttpServlet {
        private static final long serialVersionUID = 1L;
        final AtomicBoolean reached = new AtomicBoolean();

        @Override
        protected void service(jakarta.servlet.http.HttpServletRequest req,
                               jakarta.servlet.http.HttpServletResponse resp) {
            reached.set(true);
        }
    }

    @Test
    void withoutTheFilterTheSecurityChainFailsOnIt() {
        // The reason for the filter: this is the 500.
        MockHttpServletRequest r = pagesRequest("/%70ages/api/v1/me", "/pages/api/v1/me");
        assertThrows(IllegalArgumentException.class,
                () -> securityChain.doFilter(r, new MockHttpServletResponse(), new MockFilterChain()));
    }

    @Test
    void anEncodedLetterInTheServletPathAnswers400AndGoesNoFurther() throws Exception {
        Reached servlet = new Reached();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(servlet, new UnparseablePathFilter(), securityChain)
                .doFilter(pagesRequest("/%70ages/api/v1/me", "/pages/api/v1/me"), response);
        assertEquals(400, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertFalse(servlet.reached.get());
    }

    @Test
    void anOrdinaryRequestReachesTheChainUntouched() throws Exception {
        MockHttpServletRequest r = pagesRequest("/pages/api/v1/me", "/pages/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new Reached(), new UnparseablePathFilter(), securityChain).doFilter(r, response);
        // the rules answer (login redirect or 401/403); the filter did not
        assertNotEquals(400, response.getStatus());
    }

    @Test
    void theParseIsNotLeftOnTheRequest() throws Exception {
        MockHttpServletRequest r = pagesRequest("/pages/api/v1/me", "/pages/api/v1/me");
        new UnparseablePathFilter().doFilter(r, new MockHttpServletResponse(), new MockFilterChain());
        assertFalse(ServletRequestPathUtils.hasParsedRequestPath(r));
    }

    @Test
    void anEncodedLetterBelowTheServletPathIsServedAsBefore() throws Exception {
        // Only the servlet-path prefix is the problem; the rest decodes and matches as ever.
        Reached servlet = new Reached();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(servlet, new UnparseablePathFilter())
                .doFilter(pagesRequest("/pages/api/v1/%6De", "/pages/api/v1/me"), response);
        assertTrue(servlet.reached.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void itIsRegisteredAheadOfTheSecurityChain() {
        var registration = new ServletInfraConfig().unparseablePathFilter();
        // Boot registers springSecurityFilterChain at -100 (SecurityProperties.DEFAULT_FILTER_ORDER)
        assertTrue(registration.getOrder() < -100, "order " + registration.getOrder());
        assertTrue(registration.getOrder() > Ordered.HIGHEST_PRECEDENCE);
        assertTrue(registration.getUrlPatterns().contains("/*"));
        assertTrue(registration.isEnabled());
    }
}
