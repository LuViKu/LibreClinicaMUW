/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.webmvc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.Controller;

/**
 * Phase E.8 legacy-retirement Slice L1 (2026-06-20) — unit tests for
 * the {@code /login/login} redirect controller.
 */
class LegacyLoginRedirectTest {

    private final Controller controller = new WebMvcConfig().loginLoginRedirectController();

    @Test
    void emptyQueryRedirectsToBareSpaLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/login/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handleRequest(req, resp);

        assertEquals(302, resp.getStatus());
        assertEquals("/LibreClinica/app/login", resp.getHeader("Location"));
    }

    @Test
    void errorQueryStringIsPreserved() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/login/login");
        req.setQueryString("error=bad_credentials");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handleRequest(req, resp);

        assertEquals(302, resp.getStatus());
        assertEquals("/LibreClinica/app/login?error=bad_credentials", resp.getHeader("Location"));
    }

    @Test
    void returnToQueryStringIsPreserved() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/login/login");
        req.setQueryString("returnTo=/app/subjects/123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handleRequest(req, resp);

        assertEquals(302, resp.getStatus());
        assertEquals("/LibreClinica/app/login?returnTo=/app/subjects/123",
                resp.getHeader("Location"));
    }
}
