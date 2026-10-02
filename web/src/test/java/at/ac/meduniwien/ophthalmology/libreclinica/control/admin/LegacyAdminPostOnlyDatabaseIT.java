/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;

/**
 * The user, role and test-mail actions against the real schema: a GET leaves
 * every row as it was and answers 405, while the POST that the users list now
 * sends still removes, restores and unlocks. The seeded {@code physician}
 * account (an Investigator in study 1) is reset before each test.
 */
class LegacyAdminPostOnlyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String USER = "physician";
    private static final int STUDY = LegacyServletHarness.STUDY_ID;
    private static final int AVAILABLE = 1;
    private static final int REMOVED = 5;
    private static final int LOCKED = 6;
    private static final int AUTO_REMOVED = 7;

    private final UserAccountBean admin = LegacyServletHarness.sysAdmin(1, "root");
    private final List<MimeMessage> sent = new ArrayList<>();
    private LegacyServletHarness harness;
    private String userId;

    @BeforeEach
    void setUp() throws Exception {
        userId = String.valueOf(queryInt("SELECT user_id FROM user_account WHERE user_name = ?", USER));
        update("UPDATE user_account SET status_id = 1, enabled = TRUE, account_non_locked = TRUE, lock_counter = 0"
                + " WHERE user_name = ?", USER);
        update("UPDATE study_user_role SET status_id = 1 WHERE user_name = ?", USER);

        SecurityManager passwords = mock(SecurityManager.class);
        when(passwords.genPassword()).thenReturn("Reset-Passw0rd");
        when(passwords.encryptPassword(anyString(), anyBoolean())).thenReturn("{bcrypt}reset-hash");
        JavaMailSenderImpl mail = new JavaMailSenderImpl() {
            @Override
            public void send(@NonNull MimeMessage message) {
                sent.add(message);
            }
        };
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", passwords)
                .bean("mailSender", mail);
    }

    // ---- remove / restore a user -------------------------------------------------

    @Test
    void aGetDoesNotRemoveTheUser() throws Exception {
        MockHttpServletResponse resp = run(new DeleteUserServlet(), "GET", "/DeleteUser", "action", "3", "userId", userId);

        assertEquals(AVAILABLE, userStatus());
        assertEquals(AVAILABLE, roleStatus());
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aGetDoesNotRestoreTheUser() throws Exception {
        update("UPDATE user_account SET status_id = 5 WHERE user_name = ?", USER);
        String passwordBefore = password();

        MockHttpServletResponse resp = run(new DeleteUserServlet(), "GET", "/DeleteUser", "action", "4", "userId", userId);

        assertEquals(REMOVED, userStatus());
        assertEquals(passwordBefore, password(), "no new password was set");
        assertTrue(sent.isEmpty(), "no password mail was sent");
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aPostRemovesTheUserAndAPostRestoresThem() throws Exception {
        MockHttpServletResponse removed = run(new DeleteUserServlet(), "POST", "/DeleteUser", "action", "3", "userId", userId);

        assertEquals(REMOVED, userStatus());
        assertEquals(AUTO_REMOVED, roleStatus(), "the role goes with the user");
        assertEquals("/ListUserAccounts", removed.getForwardedUrl());

        run(new DeleteUserServlet(), "POST", "/DeleteUser", "action", "4", "userId", userId);

        assertEquals(AVAILABLE, userStatus());
        assertEquals(AVAILABLE, roleStatus());
        assertEquals("{bcrypt}reset-hash", password());
        assertEquals(1, sent.size(), "the restore mails the new password");
    }

    // ---- unlock a user -------------------------------------------------------------

    @Test
    void aGetDoesNotUnlockTheUser() throws Exception {
        lockUser();

        MockHttpServletResponse resp = run(new UnLockUserServlet(), "GET", "/UnLockUser", "userId", userId);

        assertFalse(accountNonLocked());
        assertEquals(LOCKED, userStatus());
        assertTrue(sent.isEmpty());
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aPostUnlocksTheUser() throws Exception {
        lockUser();

        MockHttpServletResponse resp = run(new UnLockUserServlet(), "POST", "/UnLockUser", "userId", userId);

        assertTrue(accountNonLocked());
        assertEquals(AVAILABLE, userStatus());
        assertEquals(0, queryInt("SELECT lock_counter FROM user_account WHERE user_name = ?", USER));
        assertEquals(1, sent.size(), "the unlock mails the new password");
        assertEquals("/ListUserAccounts", resp.getForwardedUrl());
    }

    // ---- remove / restore a study role -----------------------------------------------

    @Test
    void aGetDoesNotRemoveTheStudyRole() throws Exception {
        MockHttpServletResponse resp = run(new DeleteStudyUserRoleServlet(), "GET", "/DeleteStudyUserRole",
                "studyId", String.valueOf(STUDY), "userName", USER, "action", "3");

        assertEquals(AVAILABLE, roleStatus());
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aGetDoesNotRestoreTheStudyRole() throws Exception {
        update("UPDATE study_user_role SET status_id = 5 WHERE user_name = ?", USER);

        MockHttpServletResponse resp = run(new DeleteStudyUserRoleServlet(), "GET", "/DeleteStudyUserRole",
                "studyId", String.valueOf(STUDY), "userName", USER, "action", "4");

        assertEquals(REMOVED, roleStatus());
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aPostRemovesTheStudyRoleAndAPostRestoresIt() throws Exception {
        MockHttpServletResponse removed = run(new DeleteStudyUserRoleServlet(), "POST", "/DeleteStudyUserRole",
                "studyId", String.valueOf(STUDY), "userName", USER, "action", "3");

        assertEquals(REMOVED, roleStatus());
        assertEquals("/ListUserAccounts", removed.getForwardedUrl());

        run(new DeleteStudyUserRoleServlet(), "POST", "/DeleteStudyUserRole",
                "studyId", String.valueOf(STUDY), "userName", USER, "action", "4");

        assertEquals(AVAILABLE, roleStatus());
    }

    // ---- test e-mail ----------------------------------------------------------------------

    @Test
    void aGetDoesNotSendTheTestMail() throws Exception {
        MockHttpServletResponse resp = run(new SendTestEmailServlet(), "GET", "/SendTestEmail");

        assertTrue(sent.isEmpty());
        assertEquals(405, resp.getStatus());
    }

    @Test
    void aPostSendsTheTestMailToTheAdministrator() throws Exception {
        MockHttpServletResponse resp = run(new SendTestEmailServlet(), "POST", "/SendTestEmail");

        assertEquals(1, sent.size());
        assertEquals(admin.getEmail(), sent.get(0).getAllRecipients()[0].toString());
        assertTrue(resp.getContentAsString().contains("\"type\":\"success\""), resp.getContentAsString());
    }

    // ---- helpers --------------------------------------------------------------------------

    private MockHttpServletResponse run(SecureController servlet, String method, String path, String... params)
            throws Exception {
        MockHttpServletRequest req = harness.request(method, path, admin);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return harness.run(servlet, req);
    }

    private static void lockUser() throws SQLException {
        update("UPDATE user_account SET status_id = 6, account_non_locked = FALSE, lock_counter = 3"
                + " WHERE user_name = ?", USER);
    }

    private static int userStatus() throws SQLException {
        return queryInt("SELECT status_id FROM user_account WHERE user_name = ?", USER);
    }

    private static int roleStatus() throws SQLException {
        return queryInt("SELECT status_id FROM study_user_role WHERE user_name = ? AND study_id = " + STUDY, USER);
    }

    private static boolean accountNonLocked() throws SQLException {
        return queryInt("SELECT CASE WHEN account_non_locked THEN 1 ELSE 0 END FROM user_account WHERE user_name = ?",
                USER) == 1;
    }

    private static String password() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT passwd FROM user_account WHERE user_name = ?")) {
            ps.setString(1, USER);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static int queryInt(String sql, String arg) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + arg);
                return rs.getInt(1);
            }
        }
    }

    private static void update(String sql, String arg) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }
}
