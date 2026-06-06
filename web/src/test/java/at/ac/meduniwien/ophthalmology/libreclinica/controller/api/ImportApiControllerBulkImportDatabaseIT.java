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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code bulk-import} — happy-path Testcontainers IT for
 * {@code POST /api/v1/import} (multipart upload + preview).
 *
 * <p>Uploads a minimal CDISC ODM 1.3 payload against the seeded
 * {@code S_DEFAULTS1} demo study, asserts the controller's preview
 * shape: 200, a {@code previewToken} field, {@code studyOid} echoed
 * back, plus {@code subjectCount} / {@code eventCount} / {@code rowCount}
 * summary fields populated.
 *
 * <p>Metadata validation against the demo seed may surface errors
 * because the demo seed uses different StudyEventOIDs
 * (SE_V1_INCLUSION etc.); regardless the upload + unmarshal + preview-
 * projection paths execute and produce the documented response shape
 * (issues / errorCount are part of that shape).
 *
 * <p>Persistence (commit) is intentionally deferred — the controller
 * docstring already documents {@code 501 Not Implemented} on the
 * commit path until the persistence harmoniser lands.
 */
class ImportApiControllerBulkImportDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String ODM_PAYLOAD = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"\n"
            + "     ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
            + "     FileOID=\"LCMUW_E6_IT\" CreationDateTime=\"2026-06-06T00:00:00Z\">\n"
            + "  <ClinicalData StudyOID=\"S_DEFAULTS1\" MetaDataVersionOID=\"v1.0\">\n"
            + "    <SubjectData SubjectKey=\"M-001\">\n"
            + "      <StudyEventData StudyEventOID=\"SE_V1_INCLUSION\">\n"
            + "        <FormData FormOID=\"F_DEMOGRAPHICS\">\n"
            + "          <ItemGroupData ItemGroupOID=\"IG_CORE\" TransactionType=\"Insert\">\n"
            + "            <ItemData ItemOID=\"I_HEIGHT_CM\" Value=\"175\"/>\n"
            + "          </ItemGroupData>\n"
            + "        </FormData>\n"
            + "      </StudyEventData>\n"
            + "    </SubjectData>\n"
            + "  </ClinicalData>\n"
            + "</ODM>\n";

    private MockMvc mockMvc() {
        ImportApiController controller = new ImportApiController(
                DATA_SOURCE,
                new OdmJaxbContext());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

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
    void uploadReturns200WithPreviewTokenForMinimalOdmPayload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "minimal-odm.xml", MediaType.APPLICATION_XML_VALUE,
                ODM_PAYLOAD.getBytes());

        mockMvc().perform(multipart("/api/v1/import")
                .file(file)
                .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewToken").exists())
                .andExpect(jsonPath("$.studyOid").value("S_DEFAULTS1"))
                .andExpect(jsonPath("$.subjectCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.crfCount").value(1));
    }
}
