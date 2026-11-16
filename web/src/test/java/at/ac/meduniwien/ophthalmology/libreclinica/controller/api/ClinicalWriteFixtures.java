/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.springframework.mock.web.MockHttpSession;

/**
 * Sessions and small lookups shared by the clinical-write ITs
 * ({@link ClinicalWriteRoleMatrixDatabaseIT}, {@link SdvIntegrityDatabaseIT}).
 *
 * <p>A session is bound the way {@code POST /me/activeStudy} binds it: the
 * demo account, Default Study, and the account's active role on that study.
 * The demo accounts are {@code manual_monitor} (monitor),
 * {@code manual_investigator} (Investigator), {@code manual_crc}
 * (coordinator) and {@code manual_dm} (director).
 */
final class ClinicalWriteFixtures {

    private ClinicalWriteFixtures() {}

    static MockHttpSession sessionAs(DataSource dataSource, String userName) {
        UserAccountDAO users = new UserAccountDAO(dataSource);
        UserAccountBean user = users.findByUserName(userName);
        assertTrue(user.getId() > 0, userName + " is seeded");
        StudyBean study = new StudyDAO(dataSource).findByPK(1);
        StudyUserRoleBean binding = null;
        for (StudyUserRoleBean candidate : users.findAllRolesByUserName(userName)) {
            if (candidate.getStudyId() == study.getId()
                    && Status.AVAILABLE.equals(candidate.getStatus())) {
                binding = candidate;
            }
        }
        assertNotNull(binding, userName + " holds a role on Default Study");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        session.setAttribute("study", study);
        session.setAttribute("userRole", binding);
        return session;
    }

    /**
     * The session of an account that holds {@code role}, and only that, on
     * Default Study: a copy of {@code userName} named
     * {@code userName + "-as-" + role name}, made on first use. There is no
     * ra or ra2 demo account.
     *
     * <p>The copy holds the role in the database as well as on the session.
     * An account may do what any of its roles allows (DR-042), and the write
     * rules read its other bindings, so a session that only carried another
     * role on {@code userName} would still be {@code userName}'s own role too.
     */
    static MockHttpSession sessionHolding(DataSource dataSource, String userName, Role role) {
        String rawRole = RAW_ROLE_NAMES[role.getId()];
        String copy = userName + "-as-" + rawRole.toLowerCase(java.util.Locale.ROOT);
        try {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement account = c.prepareStatement(
                         "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, "
                                 + "active_study, institutional_affiliation, status_id, owner_id, date_created, "
                                 + "user_type_id, enabled, account_non_locked, lock_counter, run_webservices, "
                                 + "authtype, enable_api_key) "
                                 + "SELECT ?, passwd, first_name, last_name, email, active_study, "
                                 + "institutional_affiliation, status_id, owner_id, now(), user_type_id, enabled, "
                                 + "account_non_locked, lock_counter, run_webservices, authtype, enable_api_key "
                                 + "FROM user_account WHERE user_name = ? "
                                 + "AND NOT EXISTS (SELECT 1 FROM user_account WHERE user_name = ?)");
                 PreparedStatement binding = c.prepareStatement(
                         "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, "
                                 + "date_created, user_name) SELECT ?, 1, 1, 1, now(), ? "
                                 + "WHERE NOT EXISTS (SELECT 1 FROM study_user_role WHERE user_name = ?)")) {
                account.setString(1, copy);
                account.setString(2, userName);
                account.setString(3, copy);
                account.executeUpdate();
                binding.setString(1, rawRole);
                binding.setString(2, copy);
                binding.setString(3, copy);
                binding.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not seed " + copy, e);
        }
        return sessionAs(dataSource, copy);
    }

    /** The {@code role_name} a role is stored under, by legacy role id. */
    private static final String[] RAW_ROLE_NAMES = {
            "invalid", "admin", "coordinator", "director", "Investigator", "ra", "monitor", "ra2"};

    static int userId(DataSource dataSource, String userName) {
        return new UserAccountDAO(dataSource).findByUserName(userName).getId();
    }

    static boolean sdvStatus(DataSource dataSource, int eventCrfId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sdv_status FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    static void setSdvStatus(DataSource dataSource, int eventCrfId, boolean verified)
            throws SQLException {
        execute(dataSource, "UPDATE event_crf SET sdv_status = " + verified
                + " WHERE event_crf_id = " + eventCrfId);
    }

    /** The value of {@code itemOid} in row {@code ordinal} of the event CRF, or null. */
    static String storedValue(DataSource dataSource, int eventCrfId, String itemOid, int ordinal)
            throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT d.value FROM item_data d JOIN item i ON i.item_id = d.item_id "
                             + "WHERE d.event_crf_id = ? AND i.oc_oid = ? AND d.ordinal = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            ps.setInt(3, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** Run an INSERT … RETURNING of one int column, and return it. */
    static int insert(DataSource dataSource, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
