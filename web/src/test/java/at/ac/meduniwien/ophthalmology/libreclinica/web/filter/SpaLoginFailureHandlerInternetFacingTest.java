/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;

import at.ac.meduniwien.ophthalmology.libreclinica.control.login.AccountConfigurationException;

/** The internet-facing switch of {@link SpaLoginFailureHandler}: one failure for every cause. */
class SpaLoginFailureHandlerInternetFacingTest {

    private static final String BAD = "/pages/login/login?action=errorLogin";

    private static SpaLoginFailureHandler handler(boolean internetFacing) {
        ExceptionMappingAuthenticationFailureHandler legacy = new ExceptionMappingAuthenticationFailureHandler();
        legacy.setDefaultFailureUrl(BAD);
        legacy.setExceptionMappings(Map.of(
                LockedException.class.getName(), "/pages/login/login?action=errorLocked",
                AccountConfigurationException.class.getName(), "/pages/login/login?action=2faOutdated"));
        return new SpaLoginFailureHandler(legacy, internetFacing);
    }

    private static MockHttpServletResponse spa(SpaLoginFailureHandler h, AuthenticationException e) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/j_spring_security_check");
        req.addHeader("Accept", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        h.onAuthenticationFailure(req, resp, e);
        return resp;
    }

    private static String legacyRedirect(SpaLoginFailureHandler h, AuthenticationException e) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        h.onAuthenticationFailure(new MockHttpServletRequest("POST", "/j_spring_security_check"), resp, e);
        return resp.getRedirectedUrl();
    }

    @Test
    void internalDeployment_stillNamesLockedAnd2fa() throws Exception {
        SpaLoginFailureHandler h = handler(false);
        assertEquals("{\"error\":\"locked\",\"message\":\"The account is locked.\"}",
                spa(h, new LockedException("x")).getContentAsString());
        assertEquals("/pages/login/login?action=errorLocked", legacyRedirect(h, new LockedException("x")));
    }

    @Test
    void internetFacing_spaFailureIsTheSameForEveryCause() throws Exception {
        SpaLoginFailureHandler h = handler(true);
        String bad = spa(h, new BadCredentialsException("x")).getContentAsString();
        assertEquals("{\"error\":\"bad_credentials\",\"message\":\"Invalid username or password.\"}", bad);
        assertEquals(bad, spa(h, new LockedException("x")).getContentAsString());
        assertEquals(bad, spa(h, new AccountConfigurationException()).getContentAsString());
        assertEquals(401, spa(h, new LockedException("x")).getStatus());
    }

    @Test
    void internetFacing_legacyFormFailureIsTheSameForEveryCause() throws Exception {
        SpaLoginFailureHandler h = handler(true);
        assertEquals(BAD, legacyRedirect(h, new BadCredentialsException("x")));
        assertEquals(BAD, legacyRedirect(h, new LockedException("x")));
        assertEquals(BAD, legacyRedirect(h, new AccountConfigurationException()));
    }
}
