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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.6 — MockMvc IT pinning the
 * {@link DatasetsApiController} session-guard + role-check surface,
 * spanning both the Phase 1 export-trigger endpoints and the
 * Phase 3 {@code :test-filter} validation surface.
 *
 * <p>Happy-path tests that exercise GenerateExtractFileService or
 * count the joined item_data rows end-to-end need a real DataSource +
 * Hibernate context and ride the existing legacy ExportDataset smoke
 * ITs (Phase 1) or a Testcontainers cut (Phase 3 counts).
 */
class DatasetsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new DatasetsApiController(
                mockDataSource(),
                Mockito.mock(CoreResources.class),
                Mockito.mock(RuleSetRuleDao.class)));
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/v1/studies/{studyOid}/datasets                            */
    /* ------------------------------------------------------------------ */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULT/datasets")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULT/datasets")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/v1/studies/{studyOid}/datasets/{datasetId}/files          */
    /* ------------------------------------------------------------------ */

    @Test
    void listFilesReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULT/datasets/42/files")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listFilesReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/studies/S_DEFAULT/datasets/42/files")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/v1/datasets/{datasetId}/export                           */
    /* ------------------------------------------------------------------ */

    @Test
    void triggerExportReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/42/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"odm\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void triggerExportReturns400WhenBodyMissing() throws Exception {
        // Sysadmin so the role gate passes and the missing-body path is
        // reached. Without a study binding the no-active-study guard
        // would intercept first — bind one too.
        mockMvcWith().perform(post("/api/v1/datasets/42/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULT", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("format")));
    }

    @Test
    void triggerExportReturns400OnUnsupportedFormat() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/42/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"json5\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSysadminSession(1, "root", 1, "S_DEFAULT", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Unsupported format")));
    }

    @Test
    void triggerExportReturns403ForRA() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/42/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"odm\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(7, "researcher",
                                1, "S_DEFAULT", "Default Study",
                                Role.RESEARCHASSISTANT, 1)))
                .andExpect(status().isForbidden());
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/v1/studies/{studyOid}/datasets:quick-odm                */
    /* ------------------------------------------------------------------ */

    @Test
    void quickOdmReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/studies/S_DEFAULT/datasets:quick-odm")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/v1/archived-files/{id}/download                          */
    /* ------------------------------------------------------------------ */

    @Test
    void downloadReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/archived-files/42/download")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */
    /* Role predicate                                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void roleMayExportDataSysadminAlwaysWins() {
        UserAccountBean sys = new UserAccountBean();
        sys.setId(1);
        sys.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        assertTrue(DatasetsApiController.roleMayExportData(sys, null),
                "sysadmin must be permitted even without an explicit role binding");
    }

    @Test
    void roleMayExportDataAllowsTheFiveOperationalRoles() {
        for (Role r : new Role[] { Role.STUDYDIRECTOR, Role.COORDINATOR,
                Role.INVESTIGATOR, Role.MONITOR }) {
            StudyUserRoleBean sur = new StudyUserRoleBean();
            sur.setRole(r);
            assertTrue(DatasetsApiController.roleMayExportData(plainUser(), sur),
                    "role " + r + " must be permitted to export");
        }
    }

    @Test
    void roleMayExportDataRefusesResearchAssistants() {
        StudyUserRoleBean sur = new StudyUserRoleBean();
        sur.setRole(Role.RESEARCHASSISTANT);
        assertFalse(DatasetsApiController.roleMayExportData(plainUser(), sur));
    }

    private static UserAccountBean plainUser() {
        UserAccountBean u = new UserAccountBean();
        u.setId(2);
        u.setName("operator");
        return u;
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/v1/datasets/{datasetId}:test-filter (Phase 3)            */
    /* ------------------------------------------------------------------ */

    @Test
    void testFilterReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":[]}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testFilterReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":[]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void testFilterReturns400WhenFiltersFieldMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'filters' is required")));
    }

    @Test
    void testFilterReturns400WhenBodyMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'filters' is required")));
    }

    @Test
    void testFilterReturns400OnRowMissingItemOid() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":[{\"operator\":\"=\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("itemOid is required")));
    }

    @Test
    void testFilterReturns400OnRowMissingOperator() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":[{\"itemOid\":\"I_AGE\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("operator is required")));
    }

    @Test
    void testFilterReturns400OnUnsupportedOperator() throws Exception {
        mockMvcWith().perform(post("/api/v1/datasets/0:test-filter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":[{\"itemOid\":\"I_AGE\",\"operator\":\"like\",\"value\":\"42\"}]}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("is not supported")));
    }
}
