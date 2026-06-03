/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;

/**
 * Phase E.6 — synthesises an HSSF workbook from a
 * {@link CrfVersionAuthoringRequest} payload that the legacy classic
 * CRF spreadsheet parser
 * ({@link at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableClassic})
 * can ingest verbatim.
 *
 * <p><b>Architectural decision</b>: rather than hand-rolling the
 * persistence SQL the parser already produces (and risking parity
 * drift with the XLS-upload path on every legacy parser fix), we
 * synthesise the workbook the operator <em>would have authored in
 * Excel</em> and hand it off to the existing
 * {@link CrfSpreadsheetParserService}. The only new code path is the
 * JSON-to-cells mapping in this class.
 *
 * <h2>Sheet layout produced</h2>
 *
 * <p>Three sheets matching the classic template:
 *
 * <ul>
 *   <li><b>CRF</b> — one header row + one data row carrying
 *       {@code (crfName, versionName, versionDescription, revisionNotes)}.</li>
 *   <li><b>Sections</b> — one header row + one row per authored
 *       section carrying
 *       {@code (label, title, subtitle, instructions, page_number, parent_section)}.</li>
 *   <li><b>Items</b> — one header row + one row per item with the 21
 *       columns the parser reads. Milestone A populates only the cells
 *       the parser actually requires for ST / INTEGER / BL items with
 *       a TEXT response set; the remaining cells stay blank.</li>
 * </ul>
 *
 * <p>The header rows themselves are skipped by the parser (it iterates
 * {@code k = 1; k < numRows}); their content is informational only.
 *
 * <h2>Column expectations on the Items sheet</h2>
 *
 * <p>The classic parser reads cells by short index. The cells the
 * parser <em>requires non-blank</em> are marked R below; everything
 * else is optional for Milestone A's scope.
 *
 * <pre>
 *   0  ITEM_NAME           R   word characters only
 *   1  DESCRIPTION_LABEL   R   non-blank
 *   2  LEFT_ITEM_TEXT          may be blank
 *   3  UNITS                   blank for M-A
 *   4  RIGHT_ITEM_TEXT         blank
 *   5  SECTION_LABEL       R   must match a Sections row
 *   6  GROUP_LABEL_HEADER      blank
 *   7  GROUP_LABEL_SUBHEAD     blank
 *   8  PARENT_ITEM             blank (no show-when in M-A)
 *   9  COLUMN_NUMBER           0 / blank
 *  10  PAGE_NUMBER             blank
 *  11  QUESTION_NUMBER         blank
 *  12  RESPONSE_TYPE       R   "text" (always TEXT in M-A)
 *  13  RESPONSE_LABEL          parser auto-coerces to "text" when type=text
 *  14  RESPONSE_OPTIONS_TEXT   parser auto-coerces to "text" when label=text
 *  15  RESPONSE_VALUES         parser auto-coerces to "text" when label=text
 *  16  DATA_TYPE           R   "ST" / "INT" / "BL"
 *  17  VALIDATION              blank (no regex in M-A)
 *  18  VALIDATION_ERROR_MSG    blank (required only if VALIDATION set)
 *  19  PHI                 R   numeric 0 (never PHI in M-A)
 *  20  REQUIRED            R   numeric 0 or 1
 * </pre>
 *
 * <p>For PHI (col 19) the parser reads via {@code cell.getCellType() ==
 * CELL_TYPE_NUMERIC} before falling back to the string value, so we
 * write a numeric 0 — a blank or string "0" would fail validation.
 *
 * <p>The CRF sheet's column 0 carries the CRF's existing name (the
 * one already stored against the {@code crf_id} in the DB) so the
 * parser's name-match invariant passes.
 */
@Service
public class CrfJsonToWorkbookAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(CrfJsonToWorkbookAdapter.class);

    /**
     * Synthesise the workbook and persist it to a fresh temp file the
     * parser can re-open. Returns the path so the caller can hand it to
     * {@link CrfSpreadsheetParserService#parseAndPersist}.
     *
     * @param request the authored payload from the SPA wizard
     * @param crf the parent CRF (its name lands on the CRF sheet)
     * @return absolute path to the synthesised workbook on disk
     * @throws IOException if temp-file creation or workbook write fails
     */
    public Path synthesize(CrfVersionAuthoringRequest request, CRFBean crf) throws IOException {
        // The pinned legacy POI 3.x HSSFWorkbook does not expose a
        // close() method (added in POI 4); the workbook + its
        // POIFSFileSystem are GC-managed. We only need to flush the
        // bytes through the output stream.
        HSSFWorkbook wb = new HSSFWorkbook();
        writeCrfSheet(wb, crf.getName(), request);
        writeSectionsSheet(wb, request);
        writeItemsSheet(wb, request);

        Path target = createTempWorkbookFile();
        try (OutputStream out = Files.newOutputStream(target)) {
            wb.write(out);
        }
        LOG.debug("Synthesised CRF authoring workbook at {} (sections={}, items={})",
                target, sectionCount(request), itemCount(request));
        return target;
    }

    /* ----------------------------------------------------------------- */
    /* Sheet writers                                                     */
    /* ----------------------------------------------------------------- */

    private void writeCrfSheet(HSSFWorkbook wb, String crfName, CrfVersionAuthoringRequest request) {
        HSSFSheet sheet = wb.createSheet("CRF");
        // Header row (informational — parser skips row 0).
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "CRF_NAME");
        setStringCell(header, 1, "VERSION");
        setStringCell(header, 2, "VERSION_DESCRIPTION");
        setStringCell(header, 3, "REVISION_NOTES");

        HSSFRow data = sheet.createRow(1);
        setStringCell(data, 0, nullSafe(crfName));
        setStringCell(data, 1, nullSafe(request.versionName()));
        setStringCell(data, 2, nullSafe(request.versionDescription()));
        // Revision notes is required by the classic parser — feed a
        // placeholder when the operator left it blank.
        String revision = request.revisionNotes() == null || request.revisionNotes().isBlank()
                ? "Authored via SPA wizard (Phase E.6 Milestone A)."
                : request.revisionNotes();
        setStringCell(data, 3, revision);
    }

    private void writeSectionsSheet(HSSFWorkbook wb, CrfVersionAuthoringRequest request) {
        HSSFSheet sheet = wb.createSheet("Sections");
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "SECTION_LABEL");
        setStringCell(header, 1, "SECTION_TITLE");
        setStringCell(header, 2, "SUBTITLE");
        setStringCell(header, 3, "INSTRUCTIONS");
        setStringCell(header, 4, "PAGE_NUMBER");
        setStringCell(header, 5, "PARENT_SECTION");

        int rowIdx = 1;
        if (request.sections() != null) {
            for (CrfVersionAuthoringRequest.Section section : request.sections()) {
                HSSFRow row = sheet.createRow(rowIdx++);
                setStringCell(row, 0, nullSafe(section.label()));
                setStringCell(row, 1, nullSafe(section.title()));
                setStringCell(row, 2, "");  // subtitle — unused in M-A
                setStringCell(row, 3, nullSafe(section.instructions()));
                setStringCell(row, 4, "");  // page number — unused in M-A
                setStringCell(row, 5, "");  // parent section — flat layout in M-A
            }
        }
    }

    private void writeItemsSheet(HSSFWorkbook wb, CrfVersionAuthoringRequest request) {
        HSSFSheet sheet = wb.createSheet("Items");
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "ITEM_NAME");
        setStringCell(header, 1, "DESCRIPTION_LABEL");
        setStringCell(header, 2, "LEFT_ITEM_TEXT");
        setStringCell(header, 3, "UNITS");
        setStringCell(header, 4, "RIGHT_ITEM_TEXT");
        setStringCell(header, 5, "SECTION_LABEL");
        setStringCell(header, 6, "GROUP_LABEL_HEADER");
        setStringCell(header, 7, "GROUP_LABEL_SUBHEADER");
        setStringCell(header, 8, "PARENT_ITEM");
        setStringCell(header, 9, "COLUMN_NUMBER");
        setStringCell(header, 10, "PAGE_NUMBER");
        setStringCell(header, 11, "QUESTION_NUMBER");
        setStringCell(header, 12, "RESPONSE_TYPE");
        setStringCell(header, 13, "RESPONSE_LABEL");
        setStringCell(header, 14, "RESPONSE_OPTIONS_TEXT");
        setStringCell(header, 15, "RESPONSE_VALUES");
        setStringCell(header, 16, "DATA_TYPE");
        setStringCell(header, 17, "VALIDATION");
        setStringCell(header, 18, "VALIDATION_ERROR_MESSAGE");
        setStringCell(header, 19, "PHI");
        setStringCell(header, 20, "REQUIRED");

        int rowIdx = 1;
        if (request.sections() != null) {
            for (CrfVersionAuthoringRequest.Section section : request.sections()) {
                if (section.items() == null) continue;
                for (CrfVersionAuthoringRequest.Item item : section.items()) {
                    HSSFRow row = sheet.createRow(rowIdx++);
                    setStringCell(row, 0, nullSafe(item.name()));
                    setStringCell(row, 1, nullSafe(item.descriptionLabel()));
                    setStringCell(row, 2, nullSafe(item.leftItemText()));
                    setStringCell(row, 3, "");                                // unit
                    setStringCell(row, 4, "");                                // right text
                    setStringCell(row, 5, nullSafe(section.label()));
                    setStringCell(row, 6, "");                                // group header
                    setStringCell(row, 7, "");                                // group sub-header
                    setStringCell(row, 8, "");                                // parent item
                    setStringCell(row, 9, "");                                // column number
                    setStringCell(row, 10, "");                               // page number
                    setStringCell(row, 11, "");                               // question number
                    setStringCell(row, 12, "text");                           // M-A locks RESPONSE_TYPE = text
                    setStringCell(row, 13, "text");                           // response label
                    setStringCell(row, 14, "text");                           // response options
                    setStringCell(row, 15, "text");                           // response values
                    setStringCell(row, 16, canonicalDataType(item.dataType()));
                    setStringCell(row, 17, "");                               // validation regexp
                    setStringCell(row, 18, "");                               // validation error msg
                    // PHI must be a numeric cell; the classic parser
                    // rejects non-numeric cells outright at col 19.
                    setNumericCell(row, 19, 0.0d);
                    // REQUIRED accepts numeric or string "0" / "1".
                    setNumericCell(row, 20, item.required() ? 1.0d : 0.0d);
                }
            }
        }
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /** Legacy POI 3.x took {@code short} for cell index; cast at the call site. */
    private static void setStringCell(HSSFRow row, int col, String value) {
        HSSFCell cell = row.createCell((short) col);
        cell.setCellValue(value);
    }

    private static void setNumericCell(HSSFRow row, int col, double value) {
        HSSFCell cell = row.createCell((short) col);
        cell.setCellType(HSSFCell.CELL_TYPE_NUMERIC);
        cell.setCellValue(value);
    }

    /**
     * Map our DTO's data-type token ("ST" / "INTEGER" / "BL") to the
     * legacy parser's expected upper-case spreadsheet code ("ST",
     * "INT", "BL"). Wire contract uses long names for readability; the
     * legacy {@code CODE} column on {@code ITEM_DATA_TYPE} uses the
     * short ones.
     */
    private static String canonicalDataType(String dataType) {
        if (dataType == null) return "";
        return switch (dataType.trim().toUpperCase()) {
            case "ST" -> "ST";
            case "INTEGER", "INT" -> "INT";
            case "BL", "BOOLEAN" -> "BL";
            default -> dataType.trim().toUpperCase();
        };
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static int sectionCount(CrfVersionAuthoringRequest request) {
        return request.sections() == null ? 0 : request.sections().size();
    }

    private static int itemCount(CrfVersionAuthoringRequest request) {
        if (request.sections() == null) return 0;
        int total = 0;
        for (CrfVersionAuthoringRequest.Section s : request.sections()) {
            if (s.items() != null) total += s.items().size();
        }
        return total;
    }

    /**
     * Create a fresh temp file the parser can re-open as a
     * {@link java.io.FileInputStream}. The legacy parser does not
     * tolerate ZIP-style {@code .xlsx} files, so we use a {@code .xls}
     * suffix; the workbook itself is HSSF (binary BIFF8).
     */
    private static Path createTempWorkbookFile() throws IOException {
        Path baseDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "libreclinica-crf-authoring");
        Files.createDirectories(baseDir);
        return Files.createTempFile(baseDir, "authored-", ".xls");
    }
}
