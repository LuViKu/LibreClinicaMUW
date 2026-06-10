/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 2026-06-10 — Testcontainers IT for the studyEye branch of
 * {@code PUT /api/v1/subjects/{oid}}. Pairs with the SPA edit form
 * landing on the same branch ({@code feature/muw-phase-e-subject-edit-studyeye})
 * — the SPA wires the dropdown, the controller persists, and this
 * IT pins the contract:
 *
 * <ul>
 *   <li><strong>null → "OD"</strong> sets the column (subject was
 *       created without an eye scope; data manager corrects it).</li>
 *   <li><strong>"OD" → "OU"</strong> changes the column (operator
 *       widens scope after a re-randomisation).</li>
 *   <li><strong>"LE" (invalid)</strong> rejects with 400 +
 *       {@code ValidationErrorBody.errors[].field=studyEye}.</li>
 * </ul>
 *
 * <p>Audit-row verification is out of scope here — the M3 IT already
 * pins {@code writeSubjectFieldAudit}'s shape; this IT covers the
 * studyEye-specific DB write only.
 */
class UpdateSubjectStudyEyeDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Seeded subject M-001 lives in study #1 with study_subject_id=1. */
    private static final int M001_SS_ID = 1;
    private static final String M001_OID = "M-001";

    private MockMvc mockMvc() {
        SubjectsApiController controller = buildSubjectsController();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void updateSetsStudyEyeFromNullToOd() throws Exception {
        setStudyEye(M001_SS_ID, null);

        mockMvc().perform(put("/api/v1/subjects/" + M001_OID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"F\",\"studyEye\":\"OD\"}")
                .session(investigatorSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyEye").value("OD"));

        assertEquals("OD", readStudyEye(M001_SS_ID));
    }

    @Test
    void updateChangesStudyEyeFromOdToOu() throws Exception {
        setStudyEye(M001_SS_ID, "OD");

        mockMvc().perform(put("/api/v1/subjects/" + M001_OID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"F\",\"studyEye\":\"OU\"}")
                .session(investigatorSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyEye").value("OU"));

        assertEquals("OU", readStudyEye(M001_SS_ID));
    }

    @Test
    void updateRejectsInvalidStudyEye() throws Exception {
        setStudyEye(M001_SS_ID, "OD");

        mockMvc().perform(put("/api/v1/subjects/" + M001_OID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"F\",\"studyEye\":\"LE\"}")
                .session(investigatorSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'studyEye')]").exists());

        // DB unchanged — the validator gates before the DAO write.
        assertEquals("OD", readStudyEye(M001_SS_ID));
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private MockHttpSession investigatorSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.INVESTIGATOR);
        role.setStudyId(1);
        session.setAttribute("userRole", role);
        return session;
    }

    private void setStudyEye(int studySubjectId, String eye) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
            if (eye == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, eye);
            }
            ps.setInt(2, studySubjectId);
            ps.executeUpdate();
        }
    }

    private String readStudyEye(int studySubjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_eye FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        }
    }
}
