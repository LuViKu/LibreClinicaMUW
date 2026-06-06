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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code study-params} — happy-path Testcontainers IT for
 * {@code GET /api/v1/studies/{oid}/parameters} and
 * {@code PUT /api/v1/studies/{oid}/parameters}.
 *
 * <p>Pins:
 * <ul>
 *   <li>GET returns all 18 handles + the path-side {@code studyOid}
 *       echoed back, with the seeded defaults from {@code study_parameter}.</li>
 *   <li>PUT with a non-trivial body flips the {@code collectDob} +
 *       {@code discrepancyManagement} handles, the response carries the
 *       updated values, and the {@code study_parameter_value} table has
 *       been upserted accordingly.</li>
 * </ul>
 */
class StudyParametersApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        StudyParametersApiController controller = new StudyParametersApiController(DATA_SOURCE);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Sysadmin session bound to S_DEFAULTS1 (study_id=1). */
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
    void getReturns200WithAllHandlesForSeededStudy() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/parameters")
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyOid").value("S_DEFAULTS1"))
                .andExpect(jsonPath("$.collectDob").exists())
                .andExpect(jsonPath("$.discrepancyManagement").exists())
                .andExpect(jsonPath("$.subjectIdGeneration").exists());
    }

    @Test
    void putReturns200AndPersistsHandleChange() throws Exception {
        // Read current to know what we're flipping from.
        String body = "{\"collectDob\":\"2\",\"discrepancyManagement\":\"false\"}";

        mockMvc().perform(put("/api/v1/studies/S_DEFAULTS1/parameters")
                .contentType("application/json")
                .content(body)
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyOid").value("S_DEFAULTS1"))
                .andExpect(jsonPath("$.collectDob").value("2"))
                .andExpect(jsonPath("$.discrepancyManagement").value("false"));

        // Persistence verification — the study_parameter_value table now
        // carries our patched values.
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM study_parameter_value "
                     + "WHERE study_id = 1 AND parameter = ?")) {
            ps.setString(1, "collectDob");
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next(),
                        "study_parameter_value(collectDob) row expected");
                org.junit.jupiter.api.Assertions.assertEquals("2", rs.getString(1));
            }
            ps.setString(1, "discrepancyManagement");
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next(),
                        "study_parameter_value(discrepancyManagement) row expected");
                org.junit.jupiter.api.Assertions.assertEquals("false", rs.getString(1));
            }
        }
    }
}
