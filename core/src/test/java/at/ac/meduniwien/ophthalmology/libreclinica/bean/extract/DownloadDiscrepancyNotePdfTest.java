/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.DiscrepancyNoteThread;

/**
 * The discrepancy-note PDF, pinned by what a reader of it sees.
 *
 * <p>The PDF library under this class was swapped (iText 2.1.2 for OpenPDF,
 * DR-007). Both expose the same {@code com.lowagie.text} API, so the code
 * compiles either way; what could still move is the document itself — where a
 * table breaks across pages, how a long note wraps inside a cell, what happens
 * to a character outside the standard fonts' encoding. Each test renders one
 * entry point from a fixed fixture, extracts the text page by page with
 * PDFBox, and compares it with a golden.
 *
 * <p>The goldens were captured from iText 2.1.2 and re-captured on OpenPDF.
 * The two differ in one respect only: iText silently dropped the "≥" in the
 * entity value, OpenPDF prints it. Every line break, page break and page
 * count is the same.
 *
 * <p>The threaded export is the one the application serves
 * ({@code DiscrepancyNoteOutputServlet}, format pdf); the flat list and the
 * single note are the other two ways the class writes a PDF.
 *
 * <p>A golden that is missing or differs fails the test, and the produced
 * text is written to {@code target/golden-capture/} for inspection.
 */
public class DownloadDiscrepancyNotePdfTest {

    private static final String STUDY_IDENTIFIER = "S_DEFAULTS1";

    /**
     * Read from the source tree, like the retinal fixtures: the build copies
     * only .xml and .properties test resources onto the classpath.
     */
    private static final Path GOLDEN_DIR = Paths.get("src/test/resources",
            DownloadDiscrepancyNotePdfTest.class.getPackageName().replace('.', '/'),
            "golden", DownloadDiscrepancyNotePdfTest.class.getSimpleName());

    private TimeZone savedTimeZone;

    @Before
    public void setUp() {
        // Term.getName() resolves through the thread's bundle.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        // The thread header prints Date.toString(), which follows the zone.
        savedTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(savedTimeZone);
    }

    /** What the application serves: threads, the study named on every page. */
    @Test
    public void threadedExportWithStudyIdentifier() throws Exception {
        CapturingStream out = new CapturingStream();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(out);

        new DownloadDiscrepancyNote().downLoadThreadedDiscBeans(
                threads(), DownloadDiscrepancyNote.PDF, response, STUDY_IDENTIFIER);

        String text = assertMatchesGolden("threads-with-study-identifier.txt", out.bytes());
        assertTrue("the fixture is meant to run over several pages",
                pageCount(text) > 1);
    }

    /** The servlet passes the identifier through from the request, which may omit it. */
    @Test
    public void threadedExportWithoutStudyIdentifier() throws Exception {
        CapturingStream out = new CapturingStream();
        new DownloadDiscrepancyNote().serializeThreadsToPDF(threads(), out, null);

        assertMatchesGolden("threads-without-study-identifier.txt", out.bytes());
    }

    @Test
    public void flatListExport() throws Exception {
        List<DiscrepancyNoteBean> notes = new ArrayList<>();
        for (DiscrepancyNoteThread thread : threads()) {
            notes.addAll(thread.getLinkedNoteList());
        }
        CapturingStream out = new CapturingStream();
        new DownloadDiscrepancyNote().downLoadDiscBeans(
                notes, DownloadDiscrepancyNote.PDF, out, STUDY_IDENTIFIER);

        assertMatchesGolden("flat-list.txt", out.bytes());
    }

    @Test
    public void singleNoteExport() throws Exception {
        CapturingStream out = new CapturingStream();
        new DownloadDiscrepancyNote().downLoad(
                threads().get(0).getLinkedNoteList().get(1), DownloadDiscrepancyNote.PDF, out);

        assertMatchesGolden("single-note.txt", out.bytes());
    }

    /* ---------------- fixture ---------------- */

    /**
     * Six threads: a parent and two replies, a parent and one reply, and a
     * parent standing alone, twice over. Enough tables for several pages, one
     * note long enough to wrap inside its cell, and text outside ASCII,
     * including one character (≥) outside WinAnsi, the standard fonts'
     * encoding.
     */
    private static List<DiscrepancyNoteThread> threads() {
        List<DiscrepancyNoteThread> threads = new ArrayList<>();
        int id = 100;
        for (int round = 0; round < 2; round++) {
            for (int replies = 2; replies >= 0; replies--) {
                id += 100;
                LinkedList<DiscrepancyNoteBean> notes = new LinkedList<>();
                DiscrepancyNoteBean parent = note(id, 0, "M-00" + (threads.size() + 1));
                notes.add(parent);
                for (int r = 1; r <= replies; r++) {
                    notes.add(note(id + r, id, parent.getSubjectName()));
                }
                threads.add(new DiscrepancyNoteThread(notes, 1));
            }
        }
        return threads;
    }

    private static DiscrepancyNoteBean note(int id, int parentId, String subject) {
        DiscrepancyNoteBean dn = new DiscrepancyNoteBean();
        dn.setId(id);
        dn.setParentDnId(parentId);
        dn.setStudyId(1);
        dn.setSubjectName(subject);
        dn.setEventName("Baseline visit");
        dn.setEventStart(new Date(1601971200000L)); // 2020-10-06T08:00:00Z
        dn.setCrfName("Optical coherence tomography");
        dn.setCrfStatus("initial data entry");
        dn.setEntityName("CST_OD");
        dn.setEntityValue("Ödem, 312 µm ≥ threshold");
        dn.setDescription(parentId == 0 ? "Value outside the expected range" : "Reply " + (id - parentId));
        dn.setDetailedNotes(parentId == 0
                ? "Central subfield thickness is above the protocol limit for this visit. "
                        + "Please check the scan quality report, confirm the segmentation was not "
                        + "misplaced by the device software, and either correct the value or "
                        + "document why it stands. Größere Abweichungen bitte mit dem Reading Center klären."
                : "Checked against the source scan; value confirmed.");
        dn.setDiscrepancyNoteTypeId(3);
        dn.setDisType(DiscrepancyNoteType.QUERY);
        dn.setResolutionStatusId(parentId == 0 ? 1 : 2);
        dn.setUpdatedDateString("2020-10-0" + (7 + (id % 3)));
        dn.setCreatedDateString("2020-10-06");
        dn.setAge(12);
        dn.setDays(parentId == 0 ? 0 : 3);
        StudySubjectBean ss = new StudySubjectBean();
        ss.setLabel(subject);
        ss.setStatus(Status.AVAILABLE);
        dn.setStudySub(ss);
        dn.getStudy().setOid(STUDY_IDENTIFIER);
        dn.getAssignedUser().setName("manual_dm");
        return dn;
    }

    /* ---------------- golden comparison ---------------- */

    /**
     * Compare the PDF's text with the golden and return the produced text.
     * The text is extracted page by page so a moved page break shows up.
     */
    private String assertMatchesGolden(String name, byte[] pdf) throws IOException {
        String produced = describe(pdf);
        Path golden = GOLDEN_DIR.resolve(name);
        String expected = Files.exists(golden)
                ? new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n")
                : null;
        if (!produced.equals(expected)) {
            Path capture = Paths.get("target", "golden-capture", getClass().getSimpleName(), name);
            Files.createDirectories(capture.getParent());
            Files.write(capture, produced.getBytes(StandardCharsets.UTF_8));
            assertEquals((expected == null ? "golden missing: " : "PDF text differs from golden: ")
                    + golden + " (produced text written to " + capture.toAbsolutePath() + ")",
                    expected, produced);
        }
        return produced;
    }

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

    private static int pageCount(String described) {
        return Integer.parseInt(described.substring("pages: ".length(), described.indexOf('\n')));
    }

    /** The class writes to a {@link ServletOutputStream}; keep what it writes. */
    private static final class CapturingStream extends ServletOutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // synchronous
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
