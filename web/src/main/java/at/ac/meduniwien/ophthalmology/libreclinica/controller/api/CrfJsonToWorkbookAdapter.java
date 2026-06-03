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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * {@link CrfVersionAuthoringRequest} payload that the legacy
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableRepeating}
 * parser can ingest verbatim.
 *
 * <p><b>Architectural decision</b>: rather than hand-rolling the
 * persistence SQL the parser already produces (and risking parity
 * drift with the XLS-upload path on every legacy parser fix), we
 * synthesise the workbook the operator <em>would have authored in
 * Excel</em> and hand it off to the existing
 * {@link CrfSpreadsheetParserService}. The only new code path is the
 * JSON-to-cells mapping in this class.
 *
 * <h2>Why the repeating template (and not classic)</h2>
 *
 * <p>The repeating parser drives the modern XLS template — 27
 * columns, a {@code Groups} sheet, default values, show-when wiring,
 * the full {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType}
 * taxonomy. Milestone B targets it so all M-B / M-C scope reaches the
 * existing parser; Milestone A's classic-template emitter is replaced
 * here.
 *
 * <h2>Sheet layout produced</h2>
 *
 * <p>Four sheets in this order:
 *
 * <ul>
 *   <li><b>CRF</b> — one header row + one data row carrying
 *       {@code (crfName, versionName, versionDescription, revisionNotes)}.</li>
 *   <li><b>Sections</b> — one header row + one row per authored
 *       section carrying
 *       {@code (label, title, subtitle, instructions, page_number, parent_section)}.</li>
 *   <li><b>Groups</b> — one header row plus (Milestone C onwards) one
 *       row per authored item group. The parser <em>requires</em>
 *       this sheet to identify a workbook as "repeating template".</li>
 *   <li><b>Items</b> — one header row + one row per item with the 27
 *       columns the repeating parser reads (see
 *       {@link at.ac.meduniwien.ophthalmology.libreclinica.core.util.CrfTemplateColumnNameEnum}).
 *       Cells the operator didn't fill are blank.</li>
 * </ul>
 *
 * <h2>Column expectations on the Items sheet (repeating template)</h2>
 *
 * <pre>
 *   0  ITEM_NAME                   R   word characters only
 *   1  DESCRIPTION_LABEL           R   non-blank
 *   2  LEFT_ITEM_TEXT                  may be blank
 *   3  UNITS                           may be blank
 *   4  RIGHT_ITEM_TEXT                 may be blank
 *   5  SECTION_LABEL               R   must match a Sections row
 *   6  GROUP_LABEL                     blank in M-B (Ungrouped default)
 *   7  HEADER                          may be blank
 *   8  SUBHEADER                       may be blank
 *   9  PARENT_ITEM                     blank in M-B (Milestone C)
 *  10  COLUMN_NUMBER                   blank
 *  11  PAGE_NUMBER                     blank
 *  12  QUESTION_NUMBER                 blank
 *  13  RESPONSE_TYPE               R   "text" / "textarea" / "radio" /
 *                                      "single-select" / "multi-select" /
 *                                      "checkbox" / "file"
 *  14  RESPONSE_LABEL              R*  parser auto-coerces to "text" for
 *                                      text/textarea
 *  15  RESPONSE_OPTIONS_TEXT       R*  comma-separated option labels
 *                                      (auto-coerces for text/textarea)
 *  16  RESPONSE_VALUES_OR_CALCS    R*  comma-separated option values
 *                                      (auto-coerces for text/textarea)
 *  17  RESPONSE_LAYOUT                 blank
 *  18  DEFAULT_VALUE                   optional
 *  19  DATA_TYPE                   R   "ST" / "INT" / "REAL" / "DATE" /
 *                                      "PDATE" / "FILE" / "BL"
 *  20  WIDTH_DECIMAL                   blank
 *  21  VALIDATION                      regexp: /.../ if set
 *  22  VALIDATION_ERROR_MESSAGE        required iff VALIDATION set
 *  23  PHI                         R   numeric 0 / 1
 *  24  REQUIRED                    R   numeric 0 / 1
 *  25  ITEM_DISPLAY_STATUS             blank in M-B (Show by default)
 *  26  SIMPLE_CONDITIONAL_DISPLAY      blank in M-B
 * </pre>
 *
 * <p>For PHI (col 23) the parser reads via {@code cell.getCellType() ==
 * CELL_TYPE_NUMERIC} before falling back to the string value, so we
 * write a numeric 0. The CRF sheet's column 0 carries the CRF's
 * existing name so the parser's name-match invariant passes.
 */
@Service
public class CrfJsonToWorkbookAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(CrfJsonToWorkbookAdapter.class);

    /**
     * Canonical response-type tokens accepted in
     * {@link CrfVersionAuthoringRequest.ResponseSet#type()}. Calculation
     * variants are deferred to Milestone C.
     */
    public static final java.util.Set<String> ALLOWED_RESPONSE_TYPES = java.util.Set.of(
            "text", "textarea", "radio", "single-select", "multi-select",
            "checkbox", "file");

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
        writeGroupsSheet(wb, request);
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
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "CRF_NAME");
        setStringCell(header, 1, "VERSION");
        setStringCell(header, 2, "VERSION_DESCRIPTION");
        setStringCell(header, 3, "REVISION_NOTES");

        HSSFRow data = sheet.createRow(1);
        setStringCell(data, 0, nullSafe(crfName));
        setStringCell(data, 1, nullSafe(request.versionName()));
        setStringCell(data, 2, nullSafe(request.versionDescription()));
        // Revision notes is required by the parser — feed a placeholder
        // when the operator left it blank.
        String revision = request.revisionNotes() == null || request.revisionNotes().isBlank()
                ? "Authored via SPA wizard (Phase E.6 Milestone B)."
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
                setStringCell(row, 2, "");  // subtitle — not authored in M-B
                setStringCell(row, 3, nullSafe(section.instructions()));
                setStringCell(row, 4, "");  // page number — not authored in M-B
                setStringCell(row, 5, "");  // parent section — flat layout
            }
        }
    }

    /**
     * Emit the {@code Groups} sheet. Its presence is what flips the
     * parser into "repeating template" mode (the parser detects it
     * during construction). Milestone B only emits the header — items
     * land in the parser-managed default "Ungrouped" group. Milestone
     * C will append authored item groups in rows 1..N.
     */
    private void writeGroupsSheet(HSSFWorkbook wb, CrfVersionAuthoringRequest request) {
        HSSFSheet sheet = wb.createSheet("Groups");
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "GROUP_LABEL");
        setStringCell(header, 1, "GROUP_LAYOUT");
        setStringCell(header, 2, "GROUP_HEADER");
        setStringCell(header, 3, "GROUP_REPEAT_NUMBER");
        setStringCell(header, 4, "GROUP_REPEAT_MAX");
        setStringCell(header, 5, "GROUP_DISPLAY_STATUS");
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
        setStringCell(header, 6, "GROUP_LABEL");
        setStringCell(header, 7, "HEADER");
        setStringCell(header, 8, "SUBHEADER");
        setStringCell(header, 9, "PARENT_ITEM");
        setStringCell(header, 10, "COLUMN_NUMBER");
        setStringCell(header, 11, "PAGE_NUMBER");
        setStringCell(header, 12, "QUESTION_NUMBER");
        setStringCell(header, 13, "RESPONSE_TYPE");
        setStringCell(header, 14, "RESPONSE_LABEL");
        setStringCell(header, 15, "RESPONSE_OPTIONS_TEXT");
        setStringCell(header, 16, "RESPONSE_VALUES_OR_CALCULATIONS");
        setStringCell(header, 17, "RESPONSE_LAYOUT");
        setStringCell(header, 18, "DEFAULT_VALUE");
        setStringCell(header, 19, "DATA_TYPE");
        setStringCell(header, 20, "WIDTH_DECIMAL");
        setStringCell(header, 21, "VALIDATION");
        setStringCell(header, 22, "VALIDATION_ERROR_MESSAGE");
        setStringCell(header, 23, "PHI");
        setStringCell(header, 24, "REQUIRED");
        setStringCell(header, 25, "ITEM_DISPLAY_STATUS");
        setStringCell(header, 26, "SIMPLE_CONDITIONAL_DISPLAY");

        int rowIdx = 1;
        if (request.sections() != null) {
            for (CrfVersionAuthoringRequest.Section section : request.sections()) {
                if (section.items() == null) continue;
                for (CrfVersionAuthoringRequest.Item item : section.items()) {
                    HSSFRow row = sheet.createRow(rowIdx++);
                    writeItemRow(row, section, item);
                }
            }
        }
    }

    private void writeItemRow(HSSFRow row,
                              CrfVersionAuthoringRequest.Section section,
                              CrfVersionAuthoringRequest.Item item) {
        ResponseSetCells rc = resolveResponseSetCells(item.responseSet());

        setStringCell(row, 0, nullSafe(item.name()));
        setStringCell(row, 1, nullSafe(item.descriptionLabel()));
        setStringCell(row, 2, nullSafe(item.leftItemText()));
        setStringCell(row, 3, nullSafe(item.units()));
        setStringCell(row, 4, nullSafe(item.rightItemText()));
        setStringCell(row, 5, nullSafe(section.label()));
        setStringCell(row, 6, "");                                      // group label (M-C)
        setStringCell(row, 7, "");                                      // header (M-C)
        setStringCell(row, 8, "");                                      // subheader (M-C)
        setStringCell(row, 9, "");                                      // parent item (M-C)
        setStringCell(row, 10, "");                                     // column number
        setStringCell(row, 11, "");                                     // page number
        setStringCell(row, 12, "");                                     // question number
        setStringCell(row, 13, rc.responseType);
        setStringCell(row, 14, rc.responseLabel);
        setStringCell(row, 15, rc.optionsText);
        setStringCell(row, 16, rc.optionsValues);
        setStringCell(row, 17, "");                                     // response layout
        setStringCell(row, 18, nullSafe(item.defaultValue()));
        setStringCell(row, 19, canonicalDataType(item.dataType()));
        setStringCell(row, 20, "");                                     // width_decimal
        setStringCell(row, 21, formatValidationCell(item.validation()));
        setStringCell(row, 22, item.validation() == null ? "" : nullSafe(item.validation().errorMessage()));
        // PHI must be numeric — see class javadoc.
        setNumericCell(row, 23, 0.0d);
        setNumericCell(row, 24, item.required() ? 1.0d : 0.0d);
        setStringCell(row, 25, "");                                     // ITEM_DISPLAY_STATUS (M-C)
        setStringCell(row, 26, "");                                     // SIMPLE_CONDITIONAL_DISPLAY (M-C)
    }

    /* ----------------------------------------------------------------- */
    /* Response-set translation                                          */
    /* ----------------------------------------------------------------- */

    /**
     * Triplet returned by
     * {@link #resolveResponseSetCells(CrfVersionAuthoringRequest.ResponseSet)}.
     * Holds the four-cell tuple the repeating parser reads at columns
     * 13–16.
     */
    private static final class ResponseSetCells {
        final String responseType;
        final String responseLabel;
        final String optionsText;
        final String optionsValues;

        ResponseSetCells(String responseType, String responseLabel,
                         String optionsText, String optionsValues) {
            this.responseType = responseType;
            this.responseLabel = responseLabel;
            this.optionsText = optionsText;
            this.optionsValues = optionsValues;
        }
    }

    /**
     * Render the per-item response set into the four cells the
     * repeating parser reads at columns 13 (response_type), 14
     * (response_label), 15 (options_text), 16 (options_values).
     *
     * <p>Defaults: a missing {@link CrfVersionAuthoringRequest.ResponseSet}
     * produces a TEXT response set (matches Milestone A's contract).
     * For text / textarea the parser auto-coerces label / options /
     * values to {@code "text"} / {@code "textarea"} so any value works,
     * but we emit the canonical {@code "text"} string for hygiene.
     */
    private ResponseSetCells resolveResponseSetCells(CrfVersionAuthoringRequest.ResponseSet rs) {
        if (rs == null) {
            return new ResponseSetCells("text", "text", "text", "text");
        }
        String type = rs.type() == null ? "" : rs.type().trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) type = "text";

        if ("text".equals(type) || "textarea".equals(type)) {
            return new ResponseSetCells(type, type, type, type);
        }
        if ("file".equals(type)) {
            return new ResponseSetCells("file", "file", "file", "file");
        }

        String label = rs.label() == null ? "" : rs.label().trim();
        if (label.isEmpty()) {
            // The parser requires a non-empty label for non-text
            // response sets; synthesise a stable token rather than
            // letting the parser reject. The label uniqueness gate is
            // per-CRF, not per-version, so a generic placeholder works.
            label = type.replace("-", "_") + "_options";
        }

        String optionsText = joinOptions(rs.options(), CrfVersionAuthoringRequest.Option::text);
        String optionsValues = joinOptions(rs.options(), CrfVersionAuthoringRequest.Option::value);
        return new ResponseSetCells(type, label, optionsText, optionsValues);
    }

    /** Functional handle for joining the option text or value columns. */
    @FunctionalInterface
    private interface OptionPicker {
        String pick(CrfVersionAuthoringRequest.Option opt);
    }

    /**
     * Comma-join the option text / value column. Empty options list
     * yields an empty string — the parser will then reject the row
     * (response-options column required for non-text types), which is
     * the right outcome.
     */
    private static String joinOptions(List<CrfVersionAuthoringRequest.Option> options, OptionPicker pick) {
        if (options == null || options.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) out.append(',');
            String raw = pick.pick(options.get(i));
            // Mirror legacy convention: literal commas inside an option
            // are escaped as "\\,". The parser un-escapes them after
            // split. Operators authoring inline won't typically embed
            // commas; safe to escape proactively.
            out.append(raw == null ? "" : raw.replace(",", "\\,"));
        }
        return out.toString();
    }

    /* ----------------------------------------------------------------- */
    /* Validation cell                                                   */
    /* ----------------------------------------------------------------- */

    /**
     * Render the per-item validation into the workbook's
     * VALIDATION cell. The repeating parser accepts the legacy
     * {@code regexp: /.../} clause; we wrap the raw pattern so the
     * existing parse path lights up.
     */
    private static String formatValidationCell(CrfVersionAuthoringRequest.Validation validation) {
        if (validation == null) return "";
        String regexp = validation.regexp();
        if (regexp == null || regexp.trim().isEmpty()) return "";
        String trimmed = regexp.trim();
        if (trimmed.startsWith("regexp:") || trimmed.startsWith("func:")) {
            // Operator typed the full clause; pass through.
            return trimmed;
        }
        // Strip any wrapping slashes — we add them in canonical form.
        if (trimmed.startsWith("/") && trimmed.endsWith("/") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return "regexp: /" + trimmed + "/";
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
     * Map our DTO's data-type token to the legacy parser's expected
     * spreadsheet code. The wire contract accepts both long names
     * ({@code "INTEGER"}, {@code "BOOLEAN"}) and short ones
     * ({@code "INT"}, {@code "BL"}); the parser only knows the short
     * forms.
     */
    private static final Map<String, String> DATA_TYPE_CANONICAL;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ST", "ST");
        m.put("INTEGER", "INT");
        m.put("INT", "INT");
        m.put("REAL", "REAL");
        m.put("DATE", "DATE");
        m.put("PDATE", "PDATE");
        m.put("FILE", "FILE");
        m.put("BL", "BL");
        m.put("BOOLEAN", "BL");
        DATA_TYPE_CANONICAL = java.util.Collections.unmodifiableMap(m);
    }

    /** Returns the spreadsheet code, or "" for null/blank inputs. */
    private static String canonicalDataType(String dataType) {
        if (dataType == null) return "";
        String up = dataType.trim().toUpperCase(Locale.ROOT);
        return DATA_TYPE_CANONICAL.getOrDefault(up, up);
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
