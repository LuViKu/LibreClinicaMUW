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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.ClinicZone;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

/**
 * The per-subject casebook PDF, pinned by what a reader of it sees.
 *
 * <p>The PDF library under {@link SubjectExportApiController} was swapped
 * (iText 2.1.2 for OpenPDF, DR-007). Both expose the same
 * {@code com.lowagie.text} API, so the controller compiles either way; what
 * could still move is the document — page breaks, how a table cell wraps, what
 * becomes of a character the standard fonts cannot encode. Each seeded subject
 * is exported through the endpoint, the PDF's text is extracted page by page
 * with PDFBox, and the result is compared with a golden captured from the
 * iText 2.1.2 output, which OpenPDF reproduces unchanged.
 *
 * <p>The one value that changes from day to day, the "Generated" date, is
 * replaced by a placeholder before the comparison. A golden that is missing
 * or differs fails the test, and the produced text is written to
 * {@code target/golden-capture/} for inspection.
 */
@SuppressWarnings("null")
class SubjectExportPdfDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";

    /**
     * Read from the source tree: the build copies only .xml and .properties
     * test resources onto the classpath.
     */
    private static final Path GOLDEN_DIR = Paths.get("src/test/resources",
            SubjectExportPdfDatabaseIT.class.getPackageName().replace('.', '/'),
            "golden", SubjectExportPdfDatabaseIT.class.getSimpleName());

    @ParameterizedTest
    @ValueSource(strings = { "M-001", "M-002", "M-003", "M-004", "M-005", "M-006", "M-007" })
    void theCasebookPdfReadsAsBefore(String label) throws Exception {
        MvcResult res = mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/subjects/" + label + "/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"pdf\"}")
                        .session(dm()))
                .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
                "pdf export failed: " + res.getResponse().getContentAsString());
        assertEquals("application/pdf", res.getResponse().getContentType());

        String text = describe(res.getResponse().getContentAsByteArray())
                .replace(ClinicZone.today().toString(), "<today>");
        assertMatchesGolden(label + ".txt", text);
    }

    /* ---------------- harness ---------------- */

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new SubjectExportApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession dm() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(Role.STUDYDIRECTOR);
        s.setAttribute("userRole", r);
        return s;
    }

    /* ---------------- golden comparison ---------------- */

    private void assertMatchesGolden(String name, String produced) throws IOException {
        Path golden = GOLDEN_DIR.resolve(name);
        String expected = Files.exists(golden)
                ? new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n")
                : null;
        if (!produced.equals(expected)) {
            Path capture = Paths.get("target", "golden-capture", getClass().getSimpleName(), name);
            Files.createDirectories(capture.getParent());
            Files.write(capture, produced.getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, produced,
                    (expected == null ? "golden missing: " : "PDF text differs from golden: ")
                            + golden + " (produced text written to " + capture.toAbsolutePath() + ")");
        }
    }

    /** Page count, then each page's text, so a moved page break shows up. */
    private static String describe(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            StringBuilder sb = new StringBuilder();
            sb.append("pages: ").append(doc.getNumberOfPages()).append('\n');
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            // Reading order as laid out on the page, not the order the
            // library happened to write its content streams in.
            stripper.setSortByPosition(true);
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                sb.append("--- page ").append(page).append(" ---\n").append(stripper.getText(doc));
            }
            return sb.toString();
        }
    }
}
