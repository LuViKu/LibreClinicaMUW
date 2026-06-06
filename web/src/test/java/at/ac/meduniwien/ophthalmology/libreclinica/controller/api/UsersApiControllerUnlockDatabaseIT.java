/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code unlock-user} — happy-path Testcontainers IT for
 * {@code POST /api/v1/users/{username}/unlock}.
 *
 * <p>Sibling of {@link UsersApiControllerResetPasswordDatabaseIT}: pre-
 * locks a seeded user via direct SQL, calls the unlock endpoint, and
 * verifies (a) the response shape carries the freshly generated OTP
 * cleartext and (b) the persisted {@code user_account} row was flipped
 * back to {@code account_non_locked = true} with {@code lock_counter = 0}.
 *
 * <p>The mock-DataSource sibling {@link UsersApiControllerTest} cannot
 * reach this path because the controller calls {@code findByUserName}
 * + an {@code update} on the DAO, both of which round-trip through the
 * digester query catalog.
 */
class UsersApiControllerUnlockDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Lock the `physician` seed user before each test. */
    @BeforeEach
    void prelockPhysician() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "UPDATE user_account "
                    + "   SET account_non_locked = false, lock_counter = 5 "
                    + " WHERE user_name = 'physician'");
        }
    }

    private MockMvc mockMvc() {
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        Mockito.when(securityManager.genPassword()).thenReturn("Tmp-Unlock-9!");
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

    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    @Test
    void unlockReturns200AndClearsLockOnLockedUser() throws Exception {
        mockMvc().perform(post("/api/v1/users/physician/unlock")
                .contentType("application/json")
                .content("{\"sendEmail\":false}")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedPassword").value("Tmp-Unlock-9!"))
                .andExpect(jsonPath("$.user.username").value("physician"));

        // Side-effect: account_non_locked back to true, lock_counter zeroed.
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT account_non_locked, lock_counter "
                     + "FROM user_account WHERE user_name = 'physician'")) {
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next(),
                        "physician row should exist");
                org.junit.jupiter.api.Assertions.assertTrue(rs.getBoolean(1),
                        "account_non_locked should be true after unlock");
                org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(2),
                        "lock_counter should be 0 after unlock");
            }
        }

        // Side-effect: an audit_log_event row was emitted for the lock flip.
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                     + "WHERE audit_table = 'user_account' "
                     + "  AND column_name = 'account_non_locked' "
                     + "  AND new_value = 'true'")) {
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                org.junit.jupiter.api.Assertions.assertTrue(rs.getInt(1) >= 1,
                        "expected ≥1 unlock audit row");
            }
        }
    }

    @Test
    void unlockReturns409WhenAccountAlreadyUnlocked() throws Exception {
        // Pre-state correction: undo the BeforeEach lock for this test
        // so we can hit the "not currently locked" branch.
        try (Connection conn = DATA_SOURCE.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "UPDATE user_account "
                    + "   SET account_non_locked = true, lock_counter = 0 "
                    + " WHERE user_name = 'physician'");
        }

        mockMvc().perform(post("/api/v1/users/physician/unlock")
                .contentType("application/json")
                .content("{}")
                .session(sysadminSession()))
                .andExpect(status().isConflict());
    }
}
