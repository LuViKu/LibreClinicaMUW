/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase E hardening B (sysadmin audit UI) — pins the new
 * {@code GET /api/v1/audit/system} endpoint:
 *
 * <ol>
 *   <li>{@code nonAdministratorIsForbidden} — a user without sysadmin /
 *       techadmin flag receives 403; the per-study endpoint would
 *       have rendered (a subset of) the same rows.</li>
 *   <li>{@code administratorSeesFailureRow} — a sysadmin caller
 *       receives 200 + the payload contains an
 *       {@code audit_log_event_type_id=61} (OPERATION_FAILED) row that
 *       the per-study endpoint elides via its
 *       {@code is_user_visible=true} filter.</li>
 * </ol>
 */
class SystemAuditLogIT extends AbstractApiControllerDatabaseIT {

    private static AuditApiController controller() {
        return new AuditApiController(
                DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE));
    }

    private static AuditEventDAO dao() {
        return new AuditEventDAO(DATA_SOURCE);
    }

    /**
     * Session bound to user_account #1 (seeded "root") but with the
     * sysadmin flag intentionally cleared so the gate denies access.
     * Mirrors the non-admin path the SPA's auth.bootstrap() would
     * produce when an Investigator hits the system-audit endpoint
     * directly.
     */
    private static MockHttpSession nonAdminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("non-admin");
        // No SYSADMIN / TECHADMIN user types — isSysAdmin() returns false.
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        session.setAttribute("study", study);
        return session;
    }

    /**
     * Session carrying the SYSADMIN user-type, which flips
     * {@link UserAccountBean#isSysAdmin()} to true and lets the
     * {@link UserAdminAuthorization#roleMayAdministerUsers} gate pass.
     */
    private static MockHttpSession sysAdminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        // The system endpoint deliberately ignores `study` — present
        // here only so any future code path that reads it doesn't NPE.
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        session.setAttribute("study", study);
        return session;
    }

    @Test
    void nonAdministratorIsForbidden() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mockMvc.perform(get("/api/v1/audit/system").session(nonAdminSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorSeesFailureRow() throws Exception {
        // Seed a failure-audit row tagged with a unique marker.
        String marker = "B-SYSADMIN-CASE2-" + System.nanoTime();
        try {
            FailureAuditTemplate.runOrAudit(
                    dao(),
                    /* userId */ 1,
                    "study_subject",
                    /* entityId — M-001 study_subject_id */ 1,
                    "SystemAuditLogIT.case2",
                    "test-req-B-sysadmin",
                    () -> {
                        throw new SQLException("simulated " + marker);
                    });
        } catch (SQLException expected) {
            // Template rethrew — contract.
        }

        // Sanity: the failure row landed.
        assertOperationFailureRowExists(marker);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mockMvc.perform(get("/api/v1/audit/system").session(sysAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // The marker is unique so any occurrence as a substring
                // of any `after` field is conclusive — the
                // OPERATION_FAILED row's new_value carries the
                // exception class + message + reqId triple per
                // FailureAuditTemplate.
                .andExpect(jsonPath("$[?(@.after =~ /.*" + marker + ".*/)]")
                        .exists());
    }

    private static void assertOperationFailureRowExists(String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND new_value LIKE ?")) {
            ps.setString(1, "%" + marker + "%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "Expected exactly one OPERATION_FAILED row for marker "
                                    + marker + "; got " + rs.getInt(1));
                }
            }
        }
    }
}
