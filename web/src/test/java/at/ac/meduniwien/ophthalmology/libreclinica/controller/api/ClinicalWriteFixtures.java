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
 * Sessions and small lookups for the clinical-write ITs
 * ({@link ClinicalWriteRoleMatrixDatabaseIT}).
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
     * {@code userName}'s session, holding {@code role} instead of the
     * account's own. There is no ra or ra2 demo account.
     */
    static MockHttpSession sessionHolding(DataSource dataSource, String userName, Role role) {
        MockHttpSession session = sessionAs(dataSource, userName);
        StudyUserRoleBean binding = new StudyUserRoleBean();
        binding.setRole(role);
        binding.setStudyId(1);
        binding.setUserName(userName);
        session.setAttribute("userRole", binding);
        return session;
    }

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
}
