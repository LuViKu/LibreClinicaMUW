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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

/**
 * The heritage API gate against a real user_account row: a known API key
 * passes and becomes the session user; an unknown key, or the key of a
 * removed account, is refused before the controller.
 */
class ApiSecurityFilterDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private ApiSecurityFilter filter() throws Exception {
        ApiSecurityFilter f = new ApiSecurityFilter();
        java.lang.reflect.Field ds = ApiSecurityFilter.class.getDeclaredField("dataSource");
        ds.setAccessible(true);
        ds.set(f, DATA_SOURCE);
        return f;
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            ps.executeUpdate();
        }
    }

    @BeforeEach
    void giveRootAnApiKey() throws Exception {
        sql("UPDATE user_account SET api_key = '" + KEY + "', status_id = 1 WHERE user_name = 'root'");
    }

    @AfterEach
    void takeItBack() throws Exception {
        sql("UPDATE user_account SET api_key = NULL, status_id = 1 WHERE user_name = 'root'");
    }

    private static MockHttpServletRequest request(String key) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/pages/auth/api/v1/system/config");
        r.addHeader("Authorization", "Basic "
                + Base64.getEncoder().encodeToString((key + ":").getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void aKnownApiKeyPassesAndBecomesTheSessionUser() throws Exception {
        MockHttpServletRequest req = request(KEY);
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(req, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "a valid key reaches the controller");
        UserAccountBean ub = (UserAccountBean) req.getSession().getAttribute("userBean");
        assertEquals("root", ub.getName());
    }

    @Test
    void anUnknownApiKeyIsRefused() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("ffffffffffffffffffffffffffffffff"), res, chain);

        assertEquals(401, res.getStatus());
        assertFalse(chain.getRequest() != null);
    }

    @Test
    void aRemovedAccountsApiKeyIsRefused() throws Exception {
        sql("UPDATE user_account SET status_id = 5 WHERE user_name = 'root'");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request(KEY), res, chain);

        assertEquals(401, res.getStatus());
        assertTrue(chain.getRequest() == null);
    }
}
