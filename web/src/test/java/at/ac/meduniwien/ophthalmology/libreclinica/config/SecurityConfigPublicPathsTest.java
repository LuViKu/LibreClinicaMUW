package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The anonymous-access list in {@link SecurityConfig}, checked with the same
 * matchers the filter chain builds from it, against requests shaped as Tomcat
 * hands them over: context {@code /LibreClinica}, and the Spring MVC
 * controllers behind the {@code /pages} dispatcher servlet.
 */
class SecurityConfigPublicPathsTest {

    private static boolean anonymousMayReach(String servletPath, String pathInfo) {
        String uri = "/LibreClinica" + servletPath + (pathInfo == null ? "" : pathInfo);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/LibreClinica");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        for (RequestMatcher matcher : SecurityConfig.pathPatterns(SecurityConfig.PUBLIC_PATHS)) {
            if (matcher.matches(request)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSessionBatchMigrationEndpointsNeedALogin() {
        // Called by the legacy Batch CRF Migration page, which always has a
        // session; the handlers read the session's user and study.
        assertFalse(anonymousMayReach("/pages", "/api/v1/forms/migrate/preview"));
        assertFalse(anonymousMayReach("/pages", "/api/v1/forms/migrate/run"));
        assertFalse(anonymousMayReach("/pages", "/forms/migrate/migration.log/downloadLogFile"));
    }

    @Test
    void theRemovedRuleTimezoneHelpersAreNotPublic() {
        // /pages/healthcheck was OpenClinica's rule-timezone developer helper,
        // with no caller here; its controller is gone.
        assertFalse(anonymousMayReach("/pages", "/healthcheck/runtime"));
        assertFalse(anonymousMayReach("/pages", "/healthcheck/runonschedule"));
    }

    @Test
    void theSpaApiNeedsALogin() {
        assertFalse(anonymousMayReach("/pages", "/api/v1/me"));
        assertFalse(anonymousMayReach("/pages", "/api/v1/eventCrfs/1/items"));
        assertFalse(anonymousMayReach("/pages", "/api/v1/admin/login-history"));
    }

    @Test
    void thePathsMeantForAnonymousCallersStayOpen() {
        assertTrue(anonymousMayReach("/pages", "/login/login"));
        assertTrue(anonymousMayReach("/SystemStatus", null));
        assertTrue(anonymousMayReach("/pages", "/api/v1/public/oct-upload/session"));
        assertTrue(anonymousMayReach("/pages", "/api/v1/device/uploader/heartbeat"));
        assertTrue(anonymousMayReach("/pages", "/v3/api-docs/spa-api"));
        // The heritage API authenticates each call itself (ApiSecurityFilter,
        // HTTP Basic with an API key), so the chain lets it through.
        assertTrue(anonymousMayReach("/pages", "/auth/api/v1/system/status"));
    }
}
