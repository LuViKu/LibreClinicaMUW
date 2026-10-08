/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/crfs/{oid}/versions} reads the form's text fields
 * ({@code versionName} ...) through the converters the {@code pages}
 * dispatcher really has. A browser sends them as parts without a content
 * type; a {@code String} {@code @RequestPart} needs a text converter the
 * dispatcher lacks and answered 415 (see {@code ConverterListGapTest}).
 * Against a real database, so that a request that gets past the form is
 * answered by the CRF lookup.
 */
class CrfsApiControllerUploadFormDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static MockMvc mvc() {
        return PagesDispatcherMvc.standalone(new CrfsApiController(DATA_SOURCE,
                        Mockito.mock(CrfSpreadsheetParserService.class),
                        new CrfJsonToWorkbookAdapter(),
                        new CrfJsonValidator(),
                        Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMultipartFile xls() {
        return new MockMultipartFile("file", "demo.xls", "application/vnd.ms-excel", new byte[] {1, 2});
    }

    @Test
    void aVersionNameFieldIsRead() throws Exception {
        // Past the form: the CRF lookup answers, and finds none.
        mvc().perform(multipart("/api/v1/crfs/F_NO_SUCH_CRF/versions").file(xls())
                        .param("versionName", "v1.0")
                        .param("versionDescription", "d")
                        .param("revisionNotes", "n")
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm")))
                .andExpect(status().isNotFound());
    }

    @Test
    void noVersionNameIs400WithAFieldError() throws Exception {
        mvc().perform(multipart("/api/v1/crfs/F_NO_SUCH_CRF/versions").file(xls())
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_dm")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("versionName"));
    }
}
