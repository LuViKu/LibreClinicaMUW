/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code crf-library} — happy-path Testcontainers IT for
 * {@code GET /api/v1/crfs}.
 *
 * <p>The demo seed inserts the Demographics CRF (crf_id=1, crf_version
 * Demographics V1). This IT verifies the CRF library list endpoint
 * surfaces it via the wire shape the SPA's Build-Study view consumes.
 *
 * <p>Constructor collaborators that don't influence the list path
 * (parser, workbook adapter, json validator, migration service) are
 * mocked / no-arg-built — only the DataSource matters for {@code GET}.
 */
class CrfsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        CrfsApiController controller = new CrfsApiController(
                DATA_SOURCE,
                Mockito.mock(CrfSpreadsheetParserService.class),
                new CrfJsonToWorkbookAdapter(),
                new CrfJsonValidator(),
                Mockito.mock(CrfVersionMigrationService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession authenticatedRootSession() {
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
        return session;
    }

    @Test
    void listReturns200AndIncludesSeededDemographicsCrf() throws Exception {
        mockMvc().perform(get("/api/v1/crfs")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[?(@.oid == 'F_DEMOGRAPHICS')]").exists());
    }
}
