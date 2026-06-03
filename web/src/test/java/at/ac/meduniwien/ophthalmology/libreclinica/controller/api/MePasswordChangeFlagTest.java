/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.Properties;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.6 — pins the {@code mustChangePassword} flag computation in
 * {@link MeApiController#getMe} across the four legacy branches:
 *
 * <ol>
 *   <li>{@code passwd_timestamp IS NULL && change_passwd_required = 1}
 *       → {@code mustChangePassword=true, reason=first-login};</li>
 *   <li>{@code passwd_timestamp == old && change_passwd_required = 1}
 *       → {@code mustChangePassword=true, reason=rotation};</li>
 *   <li>{@code change_passwd_required = 0} → always false even when
 *       no timestamp is set (admin disabled the rotation regime);</li>
 *   <li>LDAP + SSO users → always false (IdP / directory owns the
 *       credential lifecycle per DR-014).</li>
 * </ol>
 *
 * <p>The flag computation reads two config values from the static
 * {@link SQLInitServlet#params} properties bag. Production wires this
 * during servlet init from the {@code datainfo.properties} file overlaid
 * by the {@code configuration} DB rows the bootstrap migration seeds
 * ({@code passwd_expiration_time} + {@code change_passwd_required}).
 * For the unit test we reflectively stage the same properties bag so
 * we don't have to spin a full {@code ServletContext}.
 */
class MePasswordChangeFlagTest extends AbstractApiControllerTest {

    private Properties savedParams;

    @BeforeEach
    void stageSqlInitParams() throws Exception {
        // Capture the existing static params so other tests see the
        // original state when this one finishes.
        Field f = SQLInitServlet.class.getDeclaredField("params");
        f.setAccessible(true);
        Object current = f.get(null);
        if (current == null) {
            savedParams = null;
        } else {
            savedParams = new Properties();
            savedParams.putAll((Properties) current);
        }
    }

    @AfterEach
    void restoreSqlInitParams() throws Exception {
        Field f = SQLInitServlet.class.getDeclaredField("params");
        f.setAccessible(true);
        f.set(null, savedParams == null ? new Properties() : savedParams);
    }

    private void setSqlInitField(String key, String value) throws Exception {
        Field f = SQLInitServlet.class.getDeclaredField("params");
        f.setAccessible(true);
        Properties p = (Properties) f.get(null);
        if (p == null) {
            p = new Properties();
            f.set(null, p);
        }
        p.setProperty(key, value);
    }

    private MockMvc mockMvcWith() {
        return mockMvcFor(new MeApiController(mockDataSource()));
    }

    private MockHttpSession sessionForLocalUser(int userId, String name, Date passwdTimestamp) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId);
        ub.setName(name);
        ub.setPasswd("{bcrypt}$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        ub.setPasswdTimestamp(passwdTimestamp);
        session.setAttribute("userBean", ub);
        return session;
    }

    @Test
    void firstLoginReasonWhenTimestampNullAndChangeRequired() throws Exception {
        setSqlInitField("change_passwd_required", "1");
        setSqlInitField("passwd_expiration_time", "180");

        MockHttpSession session = sessionForLocalUser(1, "fresh", null);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordChangeReason").value("first-login"));
    }

    @Test
    void rotationReasonWhenTimestampOlderThanExpirationDays() throws Exception {
        setSqlInitField("change_passwd_required", "1");
        setSqlInitField("passwd_expiration_time", "30");

        Date longAgo = new Date(System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L);
        MockHttpSession session = sessionForLocalUser(2, "stale", longAgo);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordChangeReason").value("rotation"));
    }

    @Test
    void noChangeRequiredWhenChangePasswdRequiredIsZero() throws Exception {
        setSqlInitField("change_passwd_required", "0");
        setSqlInitField("passwd_expiration_time", "30");

        // Even with no timestamp, change-required=0 should keep the
        // flag false (admin has disabled the rotation regime).
        MockHttpSession session = sessionForLocalUser(3, "permissive", null);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.passwordChangeReason").doesNotExist());
    }

    @Test
    void ldapUsersAlwaysBypassed() throws Exception {
        setSqlInitField("change_passwd_required", "1");
        setSqlInitField("passwd_expiration_time", "1");

        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(10);
        ub.setName("ldap-user");
        // UserAccountBean.isLdapUser() === passwd.equals("*"). The
        // passwd_timestamp is null (LDAP users never have a local
        // timestamp). The legacy SecureController.passwdTimeOut()
        // skips them via `!ub.isLdapUser()`.
        ub.setPasswd("*");
        ub.setPasswdTimestamp(null);
        session.setAttribute("userBean", ub);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.source").value("ldap"));
    }

    @Test
    void ssoUsersAlwaysBypassed() throws Exception {
        setSqlInitField("change_passwd_required", "1");
        setSqlInitField("passwd_expiration_time", "1");

        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(11);
        ub.setName("sso-user");
        ub.setPasswd("{bcrypt}$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        ub.setPasswdTimestamp(null);
        ub.setExternalId("eppn-sso-user@meduniwien.ac.at");
        ub.setExternalIdProvider("shibboleth-meduniwien");
        session.setAttribute("userBean", ub);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.source").value("sso"));
    }

    @Test
    void recentLocalUserNotForced() throws Exception {
        setSqlInitField("change_passwd_required", "1");
        setSqlInitField("passwd_expiration_time", "180");

        Date yesterday = new Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L);
        MockHttpSession session = sessionForLocalUser(20, "happy", yesterday);

        mockMvcWith().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.source").value("local"));
    }

}
