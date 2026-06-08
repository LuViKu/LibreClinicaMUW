/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 study-nurse polish — Testcontainers IT for
 * {@link ModalitiesApiController}.
 *
 * <p>Pins the institution-wide modality CRUD: list/create/update/delete
 * + audit emission + Admin-only gating + uniqueness 409 + unknown-OID
 * 400 + 404 on missing id. Seeded with the six default modalities
 * (BCVA_LOGMAR, BCVA_ETDRS, IOP, REFRACTION_SPH, REFRACTION_CYL,
 * LENS_STATUS) by {@code lc-muw-2026-06-08-modality.xml}, so the list
 * tests have non-empty fixtures without any per-test seeding.
 */
class ModalitiesApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        ModalitiesApiController controller = new ModalitiesApiController(DATA_SOURCE);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(1);
        session.setAttribute("userRole", role);
        return session;
    }

    private MockHttpSession investigatorSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.INVESTIGATOR);
        role.setStudyId(1);
        session.setAttribute("userRole", role);
        return session;
    }

    /* =============================================================== */
    /* GET                                                              */
    /* =============================================================== */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvc().perform(get("/api/v1/modalities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns200WithSeededModalitiesForAuthenticatedUser() throws Exception {
        // Investigator can read (any authenticated user).
        mockMvc().perform(get("/api/v1/modalities").session(investigatorSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$[?(@.code=='BCVA_LOGMAR')].itemOidOd").value(
                        Matchers.hasItem("I_VA_OD_LOGMAR")))
                .andExpect(jsonPath("$[?(@.code=='IOP')].unit").value(
                        Matchers.hasItem("mmHg")))
                .andExpect(jsonPath("$[?(@.code=='LENS_STATUS')].dataType").value(
                        Matchers.hasItem("categorical")));
    }

    /* =============================================================== */
    /* POST                                                             */
    /* =============================================================== */

    @Test
    void createReturns403ForNonAdminRole() throws Exception {
        String body = "{\"code\":\"TEST_NEW1\",\"labelEn\":\"Test\",\"labelDe\":\"Test\","
                + "\"ordinal\":100,\"itemOidOd\":\"I_IOP_OD\","
                + "\"dataType\":\"numeric\",\"unit\":\"mmHg\"}";
        mockMvc().perform(post("/api/v1/modalities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(investigatorSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReturns409ForDuplicateCode() throws Exception {
        // BCVA_LOGMAR is seeded by the changeset.
        String body = "{\"code\":\"BCVA_LOGMAR\",\"labelEn\":\"Dup\",\"labelDe\":\"Dup\","
                + "\"ordinal\":999,\"itemOidOd\":\"I_VA_OD_LOGMAR\","
                + "\"dataType\":\"numeric\",\"unit\":\"logMAR\"}";
        mockMvc().perform(post("/api/v1/modalities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(adminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("BCVA_LOGMAR")));
    }

    @Test
    void createReturns400ForUnknownOid() throws Exception {
        String body = "{\"code\":\"TEST_BADOID\",\"labelEn\":\"Bad\",\"labelDe\":\"Bad\","
                + "\"ordinal\":200,\"itemOidOd\":\"I_DOES_NOT_EXIST\","
                + "\"dataType\":\"numeric\",\"unit\":\"\"}";
        mockMvc().perform(post("/api/v1/modalities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("I_DOES_NOT_EXIST")));
    }

    @Test
    void createReturns201AndEmitsAudit() throws Exception {
        // Use a code that doesn't collide with the seed.
        String body = "{\"code\":\"TEST_CREATE1\",\"labelEn\":\"Test create\","
                + "\"labelDe\":\"Test erstellen\",\"ordinal\":300,"
                + "\"itemOidOd\":\"I_IOP_OD\",\"itemOidOs\":\"I_IOP_OS\","
                + "\"dataType\":\"numeric\",\"unit\":\"mmHg\"}";
        mockMvc().perform(post("/api/v1/modalities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(adminSession()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST_CREATE1"))
                .andExpect(jsonPath("$.modalityId").value(Matchers.greaterThan(0)));

        // Audit row of type 58 was emitted.
        int auditCount = countAuditRowsForType(ModalitiesApiController.AUDIT_TYPE_MODALITY_CREATED);
        org.junit.jupiter.api.Assertions.assertTrue(auditCount >= 1,
                "Expected at least one audit_log_event of type "
                        + ModalitiesApiController.AUDIT_TYPE_MODALITY_CREATED);
    }

    /* =============================================================== */
    /* PUT                                                              */
    /* =============================================================== */

    @Test
    void updateReturns404ForMissingId() throws Exception {
        String body = "{\"code\":\"X\",\"labelEn\":\"X\",\"labelDe\":\"X\","
                + "\"ordinal\":1,\"itemOidOd\":\"I_IOP_OD\","
                + "\"dataType\":\"numeric\"}";
        mockMvc().perform(put("/api/v1/modalities/99999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateReturns200AndAuditOnRename() throws Exception {
        // Seed one fresh row we own end-to-end.
        int id = insertModality("TEST_UPDATE1", "Initial", "Initial", 400, "I_IOP_OD", null,
                "numeric", "mmHg");

        String body = "{\"labelEn\":\"Updated EN\",\"labelDe\":\"Updated DE\","
                + "\"ordinal\":410,\"itemOidOd\":\"I_IOP_OS\","
                + "\"dataType\":\"numeric\",\"unit\":\"mmHg\"}";
        mockMvc().perform(put("/api/v1/modalities/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TEST_UPDATE1"))
                .andExpect(jsonPath("$.labelEn").value("Updated EN"))
                .andExpect(jsonPath("$.ordinal").value(410))
                .andExpect(jsonPath("$.itemOidOd").value("I_IOP_OS"));

        int auditCount = countAuditRowsForType(ModalitiesApiController.AUDIT_TYPE_MODALITY_UPDATED);
        org.junit.jupiter.api.Assertions.assertTrue(auditCount >= 1,
                "Expected at least one audit_log_event of type "
                        + ModalitiesApiController.AUDIT_TYPE_MODALITY_UPDATED);
    }

    /* =============================================================== */
    /* DELETE                                                           */
    /* =============================================================== */

    @Test
    void deleteReturns204AndSoftDeletes() throws Exception {
        int id = insertModality("TEST_DELETE1", "Del", "Del", 500, "I_IOP_OD", null,
                "numeric", "mmHg");
        mockMvc().perform(delete("/api/v1/modalities/" + id).session(adminSession()))
                .andExpect(status().isNoContent());

        // status_id flipped to 5.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status_id FROM modality WHERE modality_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                org.junit.jupiter.api.Assertions.assertEquals(5, rs.getInt(1));
            }
        }

        int auditCount = countAuditRowsForType(ModalitiesApiController.AUDIT_TYPE_MODALITY_DELETED);
        org.junit.jupiter.api.Assertions.assertTrue(auditCount >= 1,
                "Expected at least one audit_log_event of type "
                        + ModalitiesApiController.AUDIT_TYPE_MODALITY_DELETED);
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

    private int insertModality(String code, String labelEn, String labelDe, int ordinal,
                               String oidOd, String oidOs, String dataType, String unit)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO modality (code, label_en, label_de, ordinal, "
                             + "item_oid_od, item_oid_os, data_type, unit, status_id, "
                             + "date_created, created_by_user_id) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, now(), 1) "
                             + "RETURNING modality_id")) {
            ps.setString(1, code);
            ps.setString(2, labelEn);
            ps.setString(3, labelDe);
            ps.setInt(4, ordinal);
            if (oidOd == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, oidOd);
            if (oidOs == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, oidOs);
            ps.setString(7, dataType);
            if (unit == null) ps.setNull(8, java.sql.Types.VARCHAR); else ps.setString(8, unit);
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private int countAuditRowsForType(int auditType) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, auditType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
