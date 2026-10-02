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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
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
 * <p>The seven seeded casebooks fit on one page and hold only WinAnsi text, so
 * they cannot show the first and last of those. A further subject, built by
 * this class from M-001's first form, carries thirty repeats of that form's
 * rows (several pages, the item table split across them), one value too long
 * for its cell, and one with "≥" and "≤", which the standard fonts cannot
 * encode. Its golden is captured from OpenPDF. The casebook's Helvetica drops
 * both characters, so the golden records "VA  20/40 and CST  300 µm": that is
 * what the export does today, pinned here so a change to it is seen, not an
 * endorsement of it.
 *
 * <p>The one value that changes from day to day, the "Generated" date, is
 * replaced by a placeholder before the comparison. A golden that is missing
 * or differs fails the test, and the produced text is written to
 * {@code target/golden-capture/} for inspection.
 */
@SuppressWarnings("null")
class SubjectExportPdfDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";

    /** The subject this class builds; see the class comment. */
    private static final String LONG_LABEL = "M-LONG";

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
        assertMatchesGolden(label + ".txt", exportedText(label));
    }

    @Test
    void aCasebookOverSeveralPagesWithCharactersOutsideWinAnsi() throws Exception {
        seedLongCasebook();

        String text = exportedText(LONG_LABEL);

        assertTrue(text.startsWith("pages: ") && !text.startsWith("pages: 1\n"),
                "the fixture must run over more than one page: " + text.lines().findFirst().orElse(""));
        assertMatchesGolden(LONG_LABEL + ".txt", text);
    }

    /* ---------------- harness ---------------- */

    private String exportedText(String label) throws Exception {
        MvcResult res = mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/subjects/" + label + "/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"pdf\"}")
                        .session(dm()))
                .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
                "pdf export failed: " + res.getResponse().getContentAsString());
        assertEquals("application/pdf", res.getResponse().getContentType());

        return describe(res.getResponse().getContentAsByteArray())
                .replace(ClinicZone.today().toString(), "<today>");
    }

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

    /* ---------------- the long casebook ---------------- */

    /**
     * Copies M-001 with its first form that holds data into a new subject,
     * the form's rows repeated thirty times. The rows are inserted in the
     * order of the (event_crf_id, item_id, ordinal) index, so the casebook
     * lists them the same way whichever plan PostgreSQL picks for the
     * unordered item_data query behind it.
     */
    private static void seedLongCasebook() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            if (single(st, "SELECT count(*) FROM study_subject WHERE label = '" + LONG_LABEL + "'") > 0) {
                return;
            }
            int sourceEventCrf = single(st, "SELECT min(ec.event_crf_id) FROM event_crf ec"
                    + " JOIN study_event se ON se.study_event_id = ec.study_event_id"
                    + " JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id"
                    + " WHERE ss.label = 'M-001' AND ss.study_id = 1"
                    + " AND EXISTS (SELECT 1 FROM item_data d WHERE d.event_crf_id = ec.event_crf_id)");
            int sourceEvent = single(st, "SELECT study_event_id FROM event_crf WHERE event_crf_id = " + sourceEventCrf);

            int subject = cloneRow(st, "study_subject", "study_subject_id", "label = 'M-001' AND study_id = 1",
                    "label = '" + LONG_LABEL + "', oc_oid = 'SS_MLONG'");
            int event = cloneRow(st, "study_event", "study_event_id", "study_event_id = " + sourceEvent,
                    "study_subject_id = " + subject);
            int eventCrf = cloneRow(st, "event_crf", "event_crf_id", "event_crf_id = " + sourceEventCrf,
                    "study_event_id = " + event + ", study_subject_id = " + subject);

            st.execute("DROP TABLE IF EXISTS it_rows");
            st.execute("CREATE TEMP TABLE it_rows AS SELECT d.*, g AS it_repeat, d.item_data_id AS it_source"
                    + " FROM item_data d, generate_series(1, 30) g WHERE d.event_crf_id = " + sourceEventCrf);
            st.execute("UPDATE it_rows SET event_crf_id = " + eventCrf + ", ordinal = it_repeat");
            st.execute("CREATE TEMP TABLE it_sorted AS SELECT * FROM it_rows ORDER BY item_id, ordinal, it_source");
            st.execute("ALTER TABLE it_sorted DROP COLUMN it_repeat, DROP COLUMN it_source");
            st.execute("UPDATE it_sorted SET item_data_id = nextval(pg_get_serial_sequence('item_data', 'item_data_id'))");
            // The two values are set before the insert: updating item_data
            // afterwards would move those rows to the end of the heap.
            try (PreparedStatement ps = c.prepareStatement("UPDATE it_sorted SET value = ? WHERE item_data_id ="
                    + " (SELECT item_data_id FROM it_sorted ORDER BY item_data_id OFFSET ? LIMIT 1)")) {
                ps.setString(1, "VA ≥ 20/40 and CST ≤ 300 µm");
                ps.setInt(2, 0);
                ps.executeUpdate();
                ps.setString(1, "Subretinal fluid resolved after the third injection; a small pigment epithelial"
                        + " detachment persists nasally, unchanged against the previous visit, with no new"
                        + " haemorrhage and no intraretinal cysts on any of the twenty-five B-scans");
                ps.setInt(2, 1);
                ps.executeUpdate();
            }
            st.execute("INSERT INTO item_data SELECT * FROM it_sorted ORDER BY item_data_id");
            st.execute("DROP TABLE it_rows, it_sorted");
        }
    }

    private static int single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Copies the row of {@code table} matching {@code where} with a new id from
     * the column's sequence and {@code assignments} applied, so the seed need
     * not spell out every NOT NULL column of these wide tables. Returns the new id.
     */
    private static int cloneRow(Statement st, String table, String idColumn, String where, String assignments)
            throws SQLException {
        st.execute("DROP TABLE IF EXISTS it_clone");
        st.execute("CREATE TEMP TABLE it_clone AS SELECT * FROM " + table + " WHERE " + where);
        st.execute("UPDATE it_clone SET " + idColumn + " = nextval(pg_get_serial_sequence('" + table + "', '"
                + idColumn + "')), " + assignments);
        int id = single(st, "SELECT " + idColumn + " FROM it_clone");
        st.execute("INSERT INTO " + table + " SELECT * FROM it_clone");
        st.execute("DROP TABLE it_clone");
        return id;
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
