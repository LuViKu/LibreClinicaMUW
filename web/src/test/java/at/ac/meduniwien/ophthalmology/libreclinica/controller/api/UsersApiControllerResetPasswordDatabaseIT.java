/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 `auth-admin` cluster — happy-path + branch ITs for
 * {@code POST /api/v1/users/{username}/resetPassword}.
 *
 * <p>Pins the three documented MockMvc gaps from the build playbook §
 * 3.6 that the mock-DataSource sibling
 * {@link UsersApiControllerTest} cannot reach without a real DB:
 *
 * <ul>
 *   <li><strong>200</strong> happy-path on a local-credential user →
 *       wire shape carries {@code generatedPassword}.</li>
 *   <li><strong>400</strong> on an SSO-bound user (carries
 *       {@code external_id_provider}) → operator-visible identity-
 *       provider message.</li>
 *   <li><strong>400</strong> on an LDAP user (authtype contains
 *       {@code ldap}) → operator-visible directory message.</li>
 *   <li><strong>409</strong> on a disabled user
 *       ({@code status_id = 5 DELETED}) → restore-first message.</li>
 *   <li><strong>404</strong> on an unknown username.</li>
 * </ul>
 *
 * <p>{@code SecurityManager} is mocked because the happy path doesn't
 * exercise password generation in any way the IT needs to verify —
 * we pin the wire shape, not the cleartext value. {@code AuthoritiesDao}
 * is mocked for the same reason (resetPassword doesn't read it).
 *
 * <p>Seed users used here are inserted into the Testcontainers Postgres
 * in {@link #seedAuthFixtures()} rather than added to the shared
 * production-mirroring seed migration. That keeps the auth-admin
 * fixtures scoped to this IT class — the demo seed should not ship
 * directory-owned credentials, and a 409-eligible disabled user would
 * pollute Manage Users listings in dev compose.
 */
class UsersApiControllerResetPasswordDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int SSO_USER_ID = 10001;
    private static final int LDAP_USER_ID = 10002;
    private static final int DISABLED_USER_ID = 10003;

    @BeforeAll
    static void seedAuthFixtures() throws Exception {
        // Phase E.6.ci Z2: bind a locale on the test thread so that
        // StudyUserRoleBean#setRole(Role) — invoked transitively from
        // UserAccountDAO.findByUserName → findByPK(ownerId) →
        // findAllRolesByUserName(owner) — can resolve Term.getName()
        // via ResourceBundleProvider. Without this the lookup at
        // ResourceBundleProvider.getResBundle line 139 NPEs on a
        // ThreadLocal-bundle miss, which the global ApiExceptionHandler
        // surfaces as 500 to MockMvc and the IT then asserts against
        // the expected 400 / 200 status. The mock-DataSource sibling
        // AbstractApiControllerTest binds the same locale in @BeforeEach
        // — the DatabaseIT base does not, so we bind here.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);

        // Insert three additional user_account rows beyond the demo
        // seed: one SSO-bound, one LDAP-typed, one disabled. The
        // id space (10001+) stays well above the demo seed (1..4) so
        // we never clash with a future demo user.
        try (Connection conn = DATA_SOURCE.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key, external_id, external_id_provider) "
                    + "VALUES (" + SSO_USER_ID + ", 'sso-user', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'SSO', 'User', 'sso@example.invalid', 1, 'MUW (test)', 1, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false, "
                    + "'sso-principal-1', 'shibboleth-meduniwien')");
            stmt.execute(
                    "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key) "
                    + "VALUES (" + LDAP_USER_ID + ", 'ldap-user', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'Ldap', 'User', 'ldap@example.invalid', 1, 'MUW (test)', 1, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'ldap', false)");
            stmt.execute(
                    "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key) "
                    + "VALUES (" + DISABLED_USER_ID + ", 'disabled-user', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'Disabled', 'User', 'disabled@example.invalid', 1, 'MUW (test)', 5, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
        }
    }

    private MockMvc mockMvc() {
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        // Happy-path returns a deterministic generated password +
        // arbitrary hash — the IT doesn't pin the cleartext value.
        Mockito.when(securityManager.genPassword()).thenReturn("Tmp-Pw-12!");
        Mockito.when(securityManager.encryptPassword(Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn("{bcrypt}$2a$10$hashedplaceholder");
        UsersApiController controller = new UsersApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                securityManager,
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * Session with userBean.id=1 (root), study=1, AND the sysAdmin
     * flag set — base-class helper writes only id+name, leaving
     * isSysAdmin() == false, which would short-circuit at the 403
     * gate before any of the branches under test fire.
     */
    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }

    /* ====================================================================== */
    /* 200 happy path                                                         */
    /* ====================================================================== */

    @Test
    void resetPasswordReturns200AndGeneratedPasswordForLocalUser() throws Exception {
        // physician (seed user #2) — authtype=STANDARD, no
        // external_id_provider, status_id=1 → all gates pass, password
        // is reset, response carries the generated cleartext.
        mockMvc().perform(post("/api/v1/users/physician/resetPassword")
                .contentType("application/json")
                .content("{\"sendEmail\":false}")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedPassword").value("Tmp-Pw-12!"));
    }

    @Test
    void resetPasswordReturns200AndNullPasswordWhenSendEmailRequested() throws Exception {
        // monitor (seed user #3) — same shape as physician, but with
        // sendEmail:true the response withholds the cleartext per the
        // legacy displayPwd convention (A7.4 docstring).
        mockMvc().perform(post("/api/v1/users/monitor/resetPassword")
                .contentType("application/json")
                .content("{\"sendEmail\":true}")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedPassword").isEmpty());
    }

    /* ====================================================================== */
    /* 400 SSO-bound (external_id_provider populated)                          */
    /* ====================================================================== */

    @Test
    void resetPasswordReturns400ForSsoBoundUser() throws Exception {
        mockMvc().perform(post("/api/v1/users/sso-user/resetPassword")
                .contentType("application/json")
                .content("{}")
                .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("identity provider")));
    }

    /* ====================================================================== */
    /* 400 LDAP-typed (authtype contains "ldap")                              */
    /* ====================================================================== */

    @Test
    void resetPasswordReturns400ForLdapUser() throws Exception {
        mockMvc().perform(post("/api/v1/users/ldap-user/resetPassword")
                .contentType("application/json")
                .content("{}")
                .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("the directory")));
    }

    /* ====================================================================== */
    /* 409 disabled (status_id=5)                                              */
    /* ====================================================================== */

    @Test
    void resetPasswordReturns409ForDisabledUser() throws Exception {
        mockMvc().perform(post("/api/v1/users/disabled-user/resetPassword")
                .contentType("application/json")
                .content("{}")
                .session(sysadminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("disabled")));
    }

    /* ====================================================================== */
    /* 404 unknown username                                                    */
    /* ====================================================================== */

    @Test
    void resetPasswordReturns404ForUnknownUsername() throws Exception {
        mockMvc().perform(post("/api/v1/users/no-such-account/resetPassword")
                .contentType("application/json")
                .content("{}")
                .session(sysadminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No user with username")));
    }
}
