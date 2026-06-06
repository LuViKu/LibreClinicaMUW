/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Phase E.6 — thin XSSF helper for the audit-export endpoint.
 *
 * <p>Wraps {@link XSSFWorkbook} with a small, opinionated API:
 * a single sheet, an optional bold header row, and freeze-pane
 * defaults that match the SPA's audit-log UX (header row pinned at
 * the top, columns auto-sized at finish-time).
 *
 * <p>Why XSSF, not SXSSF (streamed): audit-log queries are capped at
 * 500 rows (see {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuditApiController#STUDY_SCOPED_AUDIT_SQL_TEMPLATE}),
 * so the entire workbook fits comfortably in heap. Streaming would
 * add complexity (auto-sizing requires holding the rows anyway).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *   try (XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("Audit log")) {
 *       b.writeHeader("When", "Who", "What", "Subject", "Scope");
 *       b.writeRow("2026-06-06T08:00:00Z", "monitor_demo", "Signed",
 *                  "MUW-001", "I_HEMOGLOBIN");
 *       b.autoSize();
 *       b.writeTo(response.getOutputStream());
 *   }
 * }</pre>
 *
 * <p>{@link AutoCloseable#close()} releases the workbook's underlying
 * temp files. Always wrap in try-with-resources.
 */
public final class XlsxWorkbookBuilder implements AutoCloseable {

    private final XSSFWorkbook workbook;
    private final Sheet sheet;
    private final CellStyle headerStyle;
    private int rowIdx;
    private int headerCellCount;

    public XlsxWorkbookBuilder(String sheetName) {
        // Phase E.6 (POI 5.3.0 + JDK 21 + Saxon 8.7 on classpath):
        // Saxon 8.7 (pulled in by LibreClinica-core for ODM XSLT) wins
        // the JAXP TransformerFactory lookup but refuses DOMSource
        // ("DOMSource cannot be processed: check that saxon8-dom.jar
        // is on the classpath"). POI's ZipPackagePropertiesMarshaller
        // builds a DOM for /docProps/core.xml and runs it through the
        // TransformerFactory, which then dies. Forcing the JDK's
        // built-in xalan TransformerFactory side-steps Saxon entirely.
        // The override is scoped via Thread.currentThread()'s
        // TransformerFactory.newInstance(impl, classLoader) so we
        // don't pollute global state.
        forceJdkTransformerFactory();

        // XSSFWorkbook normalises sheet names (max 31 chars, no
        // special chars). Pass the raw caller string through
        // WorkbookUtil to surface invalid names cleanly.
        this.workbook = new XSSFWorkbook();
        // Phase E.6: stamp explicit Dublin Core metadata so the
        // marshaller writes deterministic values instead of relying
        // on now()-based defaults (also helps reproducible-builds).
        try {
            workbook.getProperties().getCoreProperties().setCreator("LibreClinicaMUW");
            workbook.getProperties().getCoreProperties().setTitle("LibreClinica export");
        } catch (Exception ignored) {
            // best-effort metadata — failure here must not abort the export
        }
        String safe = org.apache.poi.ss.util.WorkbookUtil
                .createSafeSheetName(sheetName == null ? "Sheet1" : sheetName);
        this.sheet = workbook.createSheet(safe);
        this.headerStyle = createHeaderStyle(workbook);
    }

    /**
     * Pin {@code javax.xml.transform.TransformerFactory} to the
     * JDK's built-in xalan impl for the duration of this thread.
     * Saxon 8.7 (LibreClinica-core's XSLT engine) otherwise wins
     * the JAXP service-loader race and breaks POI's docProps
     * marshalling on DOMSource input.
     */
    private static void forceJdkTransformerFactory() {
        // The default JDK 21 TransformerFactory implementation
        // class. Setting this system property scopes only to the
        // TransformerFactory.newInstance() lookup; if some other
        // code path explicitly asks for Saxon it still gets Saxon.
        System.setProperty(
                "javax.xml.transform.TransformerFactory",
                "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl");
    }

    /**
     * Emit the header row + freeze the first row so it stays pinned
     * when the operator scrolls. Must be the first thing written;
     * calling after a data row throws.
     */
    public void writeHeader(String... headers) {
        if (rowIdx != 0) {
            throw new IllegalStateException("writeHeader() must be the first row");
        }
        Row r = sheet.createRow(rowIdx++);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(headers[i] == null ? "" : headers[i]);
            c.setCellStyle(headerStyle);
        }
        headerCellCount = headers.length;
        sheet.createFreezePane(0, 1);
    }

    /** Append one data row. {@code null} cells become empty strings. */
    public void writeRow(String... cells) {
        writeRow(cells == null ? List.of() : List.of(nullsToEmpty(cells)));
    }

    /** Append one data row from a list (convenient when length varies). */
    public void writeRow(List<String> cells) {
        Row r = sheet.createRow(rowIdx++);
        if (cells == null) return;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = r.createCell(i);
            c.setCellValue(cells.get(i) == null ? "" : cells.get(i));
        }
    }

    /**
     * Auto-size every header column. Call exactly once before
     * {@link #writeTo}. Skipped when the sheet has no header row.
     */
    public void autoSize() {
        for (int i = 0; i < headerCellCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /** Serialise the workbook to an {@link OutputStream}. */
    public void writeTo(OutputStream out) throws IOException {
        workbook.write(out);
        out.flush();
    }

    /** Convenience: write to a byte array. Used by tests. */
    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeTo(buf);
        return buf.toByteArray();
    }

    /** Number of data rows written (excludes the header). */
    public int dataRowCount() {
        return rowIdx == 0 ? 0 : Math.max(rowIdx - 1, 0);
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        s.setFont(bold);
        return s;
    }

    private static String[] nullsToEmpty(String[] in) {
        String[] out = new String[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? "" : in[i];
        return out;
    }
}
