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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;

/**
 * The heritage API gate: every request without usable Basic credentials is
 * answered 401 and never reaches the controller. The key lookup itself is
 * covered by {@code ApiSecurityFilterDatabaseIT}.
 */
class ApiSecurityFilterTest {

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    /** Runs the filter; returns whether the chain (the controller) was reached. */
    private static boolean reachesController(String authorization, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pages/auth/api/v1/createuseraccount");
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockFilterChain chain = new MockFilterChain();
        new ApiSecurityFilter().doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    @Test
    void aRequestWithoutCredentialsIsStoppedNotJustAnswered401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(reachesController(null, response),
                "the heritage filter sent 401 and then ran the controller anyway");
        assertEquals(401, response.getStatus());
    }

    @Test
    void anyOtherSchemeIsRefusedInsteadOfSkippingTheCheck() throws Exception {
        for (String header : new String[] {"Bearer x", "Digest username=\"a\"", "Token abc", "basic"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(reachesController(header, response), header);
            assertEquals(401, response.getStatus(), header);
        }
    }

    @Test
    void malformedBasicCredentialsAreRefused() throws Exception {
        for (String header : new String[] {basic("no-colon"), basic(":password-only"), basic("   :x"), "Basic !!!not-base64!!!"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(reachesController(header, response), header);
            assertEquals(401, response.getStatus(), header);
        }
    }

    @Test
    void theApiKeyIsTheUserPartOfBasicCredentials() {
        assertEquals("5f462a16b3b04b1b9747262968bd5d2f", ApiSecurityFilter.basicUser(basic("5f462a16b3b04b1b9747262968bd5d2f:")));
        assertEquals("key", ApiSecurityFilter.basicUser("basic " + Base64.getEncoder().encodeToString("key:ignored".getBytes())));
        assertNull(ApiSecurityFilter.basicUser(null));
        assertNull(ApiSecurityFilter.basicUser("Bearer " + Base64.getEncoder().encodeToString("key:".getBytes())));
        assertNull(ApiSecurityFilter.basicUser("Basic"));
    }

    @Test
    void removedAndLockedAccountsAreRefused() {
        assertTrue(ApiSecurityFilter.refused(Status.DELETED));
        assertTrue(ApiSecurityFilter.refused(Status.AUTO_DELETED));
        assertTrue(ApiSecurityFilter.refused(Status.LOCKED));
        assertFalse(ApiSecurityFilter.refused(Status.AVAILABLE));
        assertFalse(ApiSecurityFilter.refused(null));
    }
}
