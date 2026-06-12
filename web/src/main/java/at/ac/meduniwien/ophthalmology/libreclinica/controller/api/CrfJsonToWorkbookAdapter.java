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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *   6  GROUP_LABEL                     M-C — emit groupLabel verbatim
 *                                      (blank means "Ungrouped" default)
 *   7  HEADER                          M-C — emit header verbatim
 *   8  SUBHEADER                       M-C — emit subHeader verbatim
 *   9  PARENT_ITEM                     M-C — emit parentItemOid when
 *                                      showItem is set
 *  10  COLUMN_NUMBER                   blank
 *  11  PAGE_NUMBER                     blank
 *  12  QUESTION_NUMBER                 blank
 *  13  RESPONSE_TYPE               R   "text" / "textarea" / "radio" /
 *                                      "single-select" / "multi-select" /
 *                                      "checkbox" / "file" plus M-C:
 *                                      "calculation" / "instant-calculation" /
 *                                      "group-calculation"
 *  14  RESPONSE_LABEL              R*  parser auto-coerces to "text" for
 *                                      text/textarea; for calc variants we
 *                                      emit the type token as the label
 *  15  RESPONSE_OPTIONS_TEXT       R*  comma-separated option labels
 *                                      (auto-coerces for text/textarea); for
 *                                      calc variants we emit the formula
 *                                      (single "option" — parity with the
 *                                      legacy XLS convention where the
 *                                      formula goes in both the OPTIONS_TEXT
 *                                      and VALUES_OR_CALCS columns).
 *  16  RESPONSE_VALUES_OR_CALCS    R*  comma-separated option values
 *                                      (auto-coerces for text/textarea); for
 *                                      calc variants the formula expression
 *                                      lives here, optionally prefixed
 *                                      with "func:" — the parser
 *                                      (SpreadSheetTableRepeating:566-619)
 *                                      strips the prefix and runs
 *                                      ScoreValidator over the body.
 *  17  RESPONSE_LAYOUT                 M-C — "page-break" when
 *                                      {@code pageBreak} is true,
 *                                      otherwise blank
 *  18  DEFAULT_VALUE                   optional
 *  19  DATA_TYPE                   R   "ST" / "INT" / "REAL" / "DATE" /
 *                                      "PDATE" / "FILE" / "BL"
 *  20  WIDTH_DECIMAL                   blank
 *  21  VALIDATION                      regexp: /.../ if set
 *  22  VALIDATION_ERROR_MESSAGE        required iff VALIDATION set
 *  23  PHI                         R   numeric 0 / 1
 *  24  REQUIRED                    R   numeric 0 / 1
 *  25  ITEM_DISPLAY_STATUS             M-C — "Hide" when showItem is
 *                                      set, blank (Show) otherwise.
 *                                      The parser rejects rows where
 *                                      ITEM_DISPLAY_STATUS is anything
 *                                      other than blank or "Hide" while
 *                                      SIMPLE_CONDITIONAL_DISPLAY is
 *                                      non-blank (SpreadSheetTableRepeating:1032).
 *  26  SIMPLE_CONDITIONAL_DISPLAY      M-C — comma triple
 *                                      "parentItemOid,parentValue,message"
 *                                      when showItem is set. The parser
 *                                      splits on "," (with "\\," escapes)
 *                                      and validates the parent item +
 *                                      that the parent value exists in
 *                                      the parent's option set.
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
     * {@link CrfVersionAuthoringRequest.ResponseSet#type()}. Milestone C
     * adds the three calculation variants.
     */
    public static final java.util.Set<String> ALLOWED_RESPONSE_TYPES = java.util.Set.of(
            "text", "textarea", "radio", "single-select", "multi-select",
            "checkbox", "file",
            "calculation", "instant-calculation", "group-calculation");

    /** Calculation variants — formula text lives in {@code options[0]}. */
    public static final java.util.Set<String> CALCULATION_TYPES = java.util.Set.of(
            "calculation", "instant-calculation", "group-calculation");

    /**
     * Phase E.6 ophth-field-catalog (2026-06-11): the adapter loads the
     * catalog once per {@link #synthesize} call so any
     * {@code catalogCode}-keyed items can be materialised against the
     * current institution-wide field set. Injected lazily — the unit
     * tests that exercise free-form items only continue to use the
     * no-arg constructor (DataSource is null) + materialisation
     * short-circuits when {@code catalogCode} is null.
     */
    private final DataSource dataSource;

    /** Production wiring — Spring autowires the application DataSource. */
    @Autowired
    public CrfJsonToWorkbookAdapter(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Test wiring — keeps the legacy no-arg constructor working for
     * the in-memory test cases that don't exercise catalog
     * materialisation. {@link #synthesize} treats a null dataSource as
     * "no catalog available" and short-circuits the materialisation
     * pass.
     */
    public CrfJsonToWorkbookAdapter() {
        this.dataSource = null;
    }

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

        // Phase E.6 ophth-field-catalog (2026-06-11): pre-materialise
        // any catalogCode-keyed items against the live catalog so the
        // workbook writers operate on enriched items only — keeps the
        // sheet-emitting code free of catalog awareness.
        CrfVersionAuthoringRequest enriched = materializeCatalogItems(request);

        HSSFWorkbook wb = new HSSFWorkbook();
        writeCrfSheet(wb, crf.getName(), enriched);
        writeSectionsSheet(wb, enriched);
        writeGroupsSheet(wb, enriched);
        writeItemsSheet(wb, enriched);
        // Phase E.6 (2026-06-05): repeating parser reads
        // wb.getSheetAt(4).getRow(1).getCell(0) for the version number
        // (SpreadSheetTableRepeating.java:191). The canonical XLS
        // template carries this in an "Instructions" sheet. Emit a
        // minimal placeholder so the parser's getSheetAt(4) doesn't
        // IndexOutOfBoundsException.
        writeInstructionsSheet(wb, request);

        Path target = createTempWorkbookFile();
        try (OutputStream out = Files.newOutputStream(target)) {
            wb.write(out);
        }
        LOG.debug("Synthesised CRF authoring workbook at {} (sections={}, items={})",
                target, sectionCount(enriched), itemCount(enriched));
        return target;
    }

    /* ----------------------------------------------------------------- */
    /* Catalog materialization                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 ophth-field-catalog (2026-06-11): walk the request's
     * sections + items, swapping any {@code catalogCode}-keyed item for
     * a fully materialised one that pulls its blank fields
     * (descriptionLabel, leftItemText, rightItemText, units, dataType,
     * responseSet) from the matching catalog row. Caller-supplied
     * fields always win over catalog defaults; null means the operator
     * authored the item free-form (legacy path, no catalog).
     *
     * <p>When the DataSource is null (no-arg constructor — test wiring)
     * OR no items reference a catalog code, the request passes through
     * unchanged so the existing workbook-emit path stays bytewise
     * identical to the pre-F3 behaviour.
     */
    private CrfVersionAuthoringRequest materializeCatalogItems(CrfVersionAuthoringRequest request) {
        if (request.sections() == null) return request;
        // Cheap scan — short-circuit when no item carries a catalog code.
        boolean anyCatalogRef = false;
        for (CrfVersionAuthoringRequest.Section s : request.sections()) {
            if (s.items() == null) continue;
            for (CrfVersionAuthoringRequest.Item it : s.items()) {
                if (it.catalogCode() != null && !it.catalogCode().isBlank()) {
                    anyCatalogRef = true;
                    break;
                }
            }
            if (anyCatalogRef) break;
        }
        if (!anyCatalogRef) return request;

        Map<String, CatalogRow> catalog = loadCatalogByCode();
        // No catalog available (DataSource null or DB error) — pass
        // through. The downstream writers will treat the item as
        // free-form, which is the legacy behaviour.
        if (catalog.isEmpty()) return request;

        List<CrfVersionAuthoringRequest.Section> outSections = new ArrayList<>(request.sections().size());
        for (CrfVersionAuthoringRequest.Section section : request.sections()) {
            if (section.items() == null) {
                outSections.add(section);
                continue;
            }
            List<CrfVersionAuthoringRequest.Item> outItems = new ArrayList<>(section.items().size());
            for (CrfVersionAuthoringRequest.Item it : section.items()) {
                outItems.add(materializeItem(it, catalog));
            }
            outSections.add(new CrfVersionAuthoringRequest.Section(
                    section.label(), section.title(), section.instructions(),
                    section.ordinal(), outItems));
        }
        return new CrfVersionAuthoringRequest(
                request.versionName(), request.versionDescription(),
                request.revisionNotes(), outSections);
    }

    /**
     * Build a {@link CrfVersionAuthoringRequest.Item} that inherits
     * blank fields from the catalog entry referenced by
     * {@code item.catalogCode()}. Pass-through when no catalog code OR
     * no matching catalog row.
     */
    static CrfVersionAuthoringRequest.Item materializeItem(
            CrfVersionAuthoringRequest.Item item, Map<String, CatalogRow> catalog) {
        String code = item.catalogCode();
        if (code == null || code.isBlank()) return item;
        CatalogRow row = catalog.get(code);
        if (row == null) {
            LOG.warn("CrfJsonToWorkbookAdapter: item references unknown catalog code '{}' — "
                    + "leaving free-form fields unchanged (downstream validation may reject the row).", code);
            return item;
        }

        String descriptionLabel = preferAuthored(item.descriptionLabel(), row.labelDe);
        String leftItemText = preferAuthored(item.leftItemText(), row.labelDe);
        String rightItemText = preferAuthored(item.rightItemText(), row.unit);
        String units = preferAuthored(item.units(), row.unit);
        String dataType = preferAuthored(item.dataType(), row.dataType);

        // Synthesize a ResponseSet from the catalog's response_options
        // when the operator hasn't authored one + the catalog defines
        // options (yesno / select-one widgets).
        CrfVersionAuthoringRequest.ResponseSet responseSet = item.responseSet();
        if (responseSet == null && row.responseOptions != null && !row.responseOptions.isEmpty()) {
            List<CrfVersionAuthoringRequest.Option> opts = new ArrayList<>(row.responseOptions.size());
            for (CatalogResponseOption ro : row.responseOptions) {
                opts.add(new CrfVersionAuthoringRequest.Option(ro.label, ro.value));
            }
            String responseType = "yesno".equalsIgnoreCase(row.widget) ? "radio" : "single-select";
            responseSet = new CrfVersionAuthoringRequest.ResponseSet(
                    responseType, code.toLowerCase(Locale.ROOT), opts, /* ref */ null);
        }

        // show-when wiring from conditional_on_code / conditional_show_when_value
        String showItem = item.showItem();
        String parentItemOid = item.parentItemOid();
        if ((showItem == null || showItem.isBlank())
                && row.conditionalOnCode != null && !row.conditionalOnCode.isBlank()
                && row.conditionalShowWhenValue != null && !row.conditionalShowWhenValue.isBlank()) {
            // The wizard is responsible for sending parentItemOid (the
            // sibling _DONE item's OID) — the catalog only knows the
            // parent's catalog code, not the OID that the section
            // assigned to it. Leave parentItemOid as the operator
            // authored it; the validator catches the mismatch.
            showItem = row.conditionalShowWhenValue + "|"
                    + row.conditionalOnCode + " == " + row.conditionalShowWhenValue;
        }

        return new CrfVersionAuthoringRequest.Item(
                item.name(),
                item.oid(),
                descriptionLabel,
                leftItemText,
                rightItemText,
                units,
                dataType,
                item.defaultValue(),
                item.required(),
                responseSet,
                item.validation(),
                showItem,
                parentItemOid,
                item.header(),
                item.subHeader(),
                item.pageBreak(),
                item.groupLabel(),
                item.catalogCode()
        );
    }

    /** Operator-authored value wins over catalog default. */
    private static String preferAuthored(String authored, String catalogValue) {
        if (authored != null && !authored.isBlank()) return authored;
        return catalogValue;
    }

    /**
     * Internal projection of one catalog row — the subset of fields
     * the materialisation logic consumes. Smaller surface than
     * {@link OphthFieldCatalogDto} so the adapter doesn't need to
     * import the DTO + drag in its Swagger annotations.
     */
    static final class CatalogRow {
        final String code;
        final String labelDe;
        final String unit;
        final String dataType;
        final String widget;
        final String conditionalOnCode;
        final String conditionalShowWhenValue;
        final List<CatalogResponseOption> responseOptions;

        CatalogRow(String code, String labelDe, String unit, String dataType,
                   String widget, String conditionalOnCode, String conditionalShowWhenValue,
                   List<CatalogResponseOption> responseOptions) {
            this.code = code;
            this.labelDe = labelDe;
            this.unit = unit;
            this.dataType = dataType;
            this.widget = widget;
            this.conditionalOnCode = conditionalOnCode;
            this.conditionalShowWhenValue = conditionalShowWhenValue;
            this.responseOptions = responseOptions;
        }
    }

    /** Internal projection of one {@code response_options} entry. */
    static final class CatalogResponseOption {
        final String value;
        final String label;

        CatalogResponseOption(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /**
     * Read the catalog from the DB into a {@code code → CatalogRow}
     * map. Returns an empty map when the DataSource is null (test
     * wiring) OR on any SQL error — materialisation then short-circuits
     * to "no catalog" and items pass through unchanged.
     */
    private Map<String, CatalogRow> loadCatalogByCode() {
        if (dataSource == null) return Map.of();
        Map<String, CatalogRow> out = new HashMap<>();
        String sql = "SELECT code, label_de, unit, data_type, widget, "
                + "       conditional_on_code, conditional_show_when_value, response_options "
                + "  FROM ophth_field_catalog "
                + " WHERE status_id = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                List<CatalogResponseOption> opts = parseResponseOptions(rs.getString("response_options"));
                out.put(rs.getString("code"), new CatalogRow(
                        rs.getString("code"),
                        rs.getString("label_de"),
                        rs.getString("unit"),
                        rs.getString("data_type"),
                        rs.getString("widget"),
                        rs.getString("conditional_on_code"),
                        rs.getString("conditional_show_when_value"),
                        opts));
            }
        } catch (SQLException e) {
            LOG.warn("CrfJsonToWorkbookAdapter: failed to load ophth_field_catalog ({}); items pass through free-form.",
                    e.getMessage());
            return Map.of();
        }
        return out;
    }

    /**
     * Parse the {@code value|label,value|label,…} storage format into
     * the projected list. Mirrors the parser in
     * {@link OphthFieldCatalogApiController#parseResponseOptions}.
     */
    static List<CatalogResponseOption> parseResponseOptions(String raw) {
        List<CatalogResponseOption> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int pipe = t.indexOf('|');
            if (pipe < 0) {
                out.add(new CatalogResponseOption(t, t));
            } else {
                String value = t.substring(0, pipe).trim();
                String label = t.substring(pipe + 1).trim();
                if (value.isEmpty()) continue;
                out.add(new CatalogResponseOption(value, label.isEmpty() ? value : label));
            }
        }
        return out;
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
     * during construction).
     *
     * <p>Milestone B emitted only the header — items landed in the
     * parser-managed default "Ungrouped" group. Milestone C appends one
     * row per distinct {@code groupLabel} authored on the items, with:
     * <ul>
     *   <li>col 0 — group label (verbatim from the item)</li>
     *   <li>col 1 — group layout, hard-coded to {@code "grid"} which
     *       flips the parser into repeating-group mode (the legacy
     *       parser treats any non-"grid" value as non-repeating; M-C
     *       flat repeating groups always go in as grids).</li>
     *   <li>col 2 — group header, blank (M-C doesn't author this)</li>
     *   <li>col 3 — repeat_number, fixed to {@code "1"} (first row is
     *       always rendered)</li>
     *   <li>col 4 — repeat_max, fixed to {@code "40"} (matches the
     *       legacy default when the operator leaves the cell blank;
     *       SpreadSheetTableRepeating:1579)</li>
     *   <li>col 5 — group display status, blank (always shown)</li>
     * </ul>
     *
     * <p>The labels are emitted in first-seen order across the
     * authored sections; duplicates are collapsed.
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

        // M-C: one row per distinct authored groupLabel, first-seen
        // order. The parser dedupes by name on persistence so a stable
        // first-seen ordering keeps SQL output deterministic.
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        if (request.sections() != null) {
            for (CrfVersionAuthoringRequest.Section section : request.sections()) {
                if (section.items() == null) continue;
                for (CrfVersionAuthoringRequest.Item item : section.items()) {
                    String label = item.groupLabel();
                    if (label != null && !label.trim().isEmpty()) {
                        labels.add(label.trim());
                    }
                }
            }
        }
        int rowIdx = 1;
        for (String label : labels) {
            HSSFRow row = sheet.createRow(rowIdx++);
            setStringCell(row, 0, label);
            setStringCell(row, 1, "grid");
            setStringCell(row, 2, "");
            setStringCell(row, 3, "1");
            setStringCell(row, 4, "40");
            setStringCell(row, 5, "");
        }
    }

    /**
     * Phase E.6 (2026-06-05): the legacy repeating parser
     * ({@link at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableRepeating#toNewCRF})
     * reads {@code wb.getSheetAt(4).getRow(1).getCell(0)} for the
     * version number — that's the "Instructions" sheet position in the
     * canonical XLS template. The JSON adapter previously emitted only
     * 4 sheets (CRF, Sections, Groups, Items), so the parser tripped
     * IndexOutOfBoundsException on every authoring attempt. Emit a
     * minimal Instructions sheet whose row 1 column 0 carries the
     * version name (parser uses it as a debug-info fallback only).
     */
    private void writeInstructionsSheet(HSSFWorkbook wb, CrfVersionAuthoringRequest request) {
        HSSFSheet sheet = wb.createSheet("Instructions");
        HSSFRow header = sheet.createRow(0);
        setStringCell(header, 0, "INSTRUCTIONS");
        HSSFRow data = sheet.createRow(1);
        setStringCell(data, 0, nullSafe(request.versionName()));
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

        boolean hasShowItem = item.showItem() != null && !item.showItem().trim().isEmpty();

        setStringCell(row, 0, nullSafe(item.name()));
        setStringCell(row, 1, nullSafe(item.descriptionLabel()));
        // LEFT_ITEM_TEXT is the load-bearing per-item label the CRF
        // entry view renders above the input widget. The wizard's
        // ItemEditor exposes it as a free-text field, but operators
        // sometimes type only the laterality marker ("OD", "OS") —
        // leaving the renderer with no measurement-name to display.
        // synthesizeLeftItemText() detects that case and falls back to
        // the operator-entered descriptionLabel (or item name) wrapped
        // with the laterality marker if one is detectable from
        // name/oid. Operator-typed real labels pass through unchanged.
        setStringCell(row, 2, synthesizeLeftItemText(item));
        setStringCell(row, 3, nullSafe(item.units()));
        // RIGHT_ITEM_TEXT is the helper-hint shown below the input.
        // When the operator leaves it blank we fall back to the units
        // value so the renderer surfaces "mmHg" / "letters" / "dpt"
        // automatically. Mirrors EventCrfsApiController.buildItemDto's
        // read-side fallback chain — keeps render parity with what the
        // backend would derive even if right_item_text were empty.
        setStringCell(row, 4, synthesizeRightItemText(item));
        setStringCell(row, 5, nullSafe(section.label()));
        setStringCell(row, 6, nullSafe(item.groupLabel()));             // GROUP_LABEL (M-C)
        setStringCell(row, 7, nullSafe(item.header()));                 // HEADER (M-C)
        setStringCell(row, 8, nullSafe(item.subHeader()));              // SUBHEADER (M-C)
        setStringCell(row, 9, hasShowItem ? nullSafe(item.parentItemOid()) : ""); // PARENT_ITEM (M-C)
        setStringCell(row, 10, "");                                     // column number
        setStringCell(row, 11, "");                                     // page number
        setStringCell(row, 12, "");                                     // question number
        setStringCell(row, 13, rc.responseType);
        setStringCell(row, 14, rc.responseLabel);
        setStringCell(row, 15, rc.optionsText);
        setStringCell(row, 16, rc.optionsValues);
        setStringCell(row, 17, item.pageBreak() ? "page-break" : "");   // RESPONSE_LAYOUT (M-C page break)
        setStringCell(row, 18, nullSafe(item.defaultValue()));
        setStringCell(row, 19, canonicalDataType(item.dataType()));
        setStringCell(row, 20, "");                                     // width_decimal
        setStringCell(row, 21, formatValidationCell(item.validation()));
        setStringCell(row, 22, item.validation() == null ? "" : nullSafe(item.validation().errorMessage()));
        // PHI must be numeric — see class javadoc.
        setNumericCell(row, 23, 0.0d);
        setNumericCell(row, 24, item.required() ? 1.0d : 0.0d);
        // M-C show-when: when set, ITEM_DISPLAY_STATUS="Hide" + the
        // SIMPLE_CONDITIONAL_DISPLAY triple (parent OID, expected
        // value, message). The parser splits on "," (with "\\," escape)
        // and rejects rows where ITEM_DISPLAY_STATUS is non-blank and
        // non-"Hide" while SIMPLE_CONDITIONAL_DISPLAY is set.
        setStringCell(row, 25, hasShowItem ? "Hide" : "");
        setStringCell(row, 26, hasShowItem
                ? formatShowItemTriple(item.parentItemOid(), item.showItem())
                : "");
    }

    /**
     * Format the SIMPLE_CONDITIONAL_DISPLAY triple the legacy parser
     * expects: {@code parentItemOid,parentValue,message}.
     *
     * <p>{@code showItem} is split on the first {@code |} into
     * {@code (parentValue, message)}. If no {@code |} is present we
     * treat the whole {@code showItem} as the parent value and emit a
     * generic message — operators authoring through the SPA will
     * typically use {@code "value|message"} but the looser form keeps
     * the wire model forgiving.
     *
     * <p>Embedded commas in either component are escaped as
     * {@code "\\,"} so the parser's {@code split(",")} doesn't trip on
     * them (SpreadSheetTableRepeating:1041).
     */
    private static String formatShowItemTriple(String parentItemOid, String showItem) {
        String parent = parentItemOid == null ? "" : parentItemOid.trim();
        String raw = showItem == null ? "" : showItem.trim();
        String value;
        String message;
        int pipeIdx = raw.indexOf('|');
        if (pipeIdx >= 0) {
            value = raw.substring(0, pipeIdx).trim();
            message = raw.substring(pipeIdx + 1).trim();
            if (message.isEmpty()) message = "Hidden until " + parent + " = " + value;
        } else {
            value = raw;
            message = "Hidden until " + parent + " = " + value;
        }
        return escapeComma(parent) + "," + escapeComma(value) + "," + escapeComma(message);
    }

    /** Mirror the parser's "\\," escape convention for embedded commas. */
    private static String escapeComma(String s) {
        return s == null ? "" : s.replace(",", "\\,");
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

        // M-C calculation variants — the wire contract carries the
        // formula in options[0].value (text and value are conventionally
        // the same for calculations). The parser reads the formula from
        // col 16 (RESPONSE_VALUES_OR_CALCS); col 15 (RESPONSE_OPTIONS)
        // also carries the formula in the canonical XLS template so
        // we mirror that here.
        if (CALCULATION_TYPES.contains(type)) {
            String label = rs.label() == null ? "" : rs.label().trim();
            if (label.isEmpty()) label = type;
            String formula = extractFormula(rs);
            return new ResponseSetCells(type, label, formula, formula);
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

    /**
     * Pull the formula text from a calculation response set. The wire
     * contract carries it in {@code options[0]} (either field — text
     * and value are conventionally the same for calculations); we tolerate
     * either. The parser
     * ({@link at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableRepeating#toNewCRF})
     * strips an optional {@code "func:"} prefix and rewrites embedded
     * commas — we hand the formula through verbatim and let it.
     */
    private static String extractFormula(CrfVersionAuthoringRequest.ResponseSet rs) {
        List<CrfVersionAuthoringRequest.Option> opts = rs.options();
        if (opts == null || opts.isEmpty()) return "";
        CrfVersionAuthoringRequest.Option first = opts.get(0);
        if (first == null) return "";
        String value = first.value();
        if (value != null && !value.trim().isEmpty()) return value.trim();
        String text = first.text();
        return text == null ? "" : text.trim();
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
        // Phase E.6 (POI 5.3.0): setCellValue(double) implicitly
        // configures CellType.NUMERIC; the explicit setCellType call
        // legacy POI 3.x required has been removed from the public API.
        HSSFCell cell = row.createCell((short) col);
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

    /* ----------------------------------------------------------------- */
    /* LEFT_ITEM_TEXT / RIGHT_ITEM_TEXT label-synthesis fallback         */
    /* ----------------------------------------------------------------- */

    /**
     * Synthesise the {@code LEFT_ITEM_TEXT} cell — the per-item label
     * the CRF entry view renders above each input widget.
     *
     * <p>The wizard's {@code ItemEditor.vue} exposes {@code leftItemText}
     * as a free-text field, but operators sometimes type only the
     * laterality marker ({@code "OD"}, {@code "OS"}, {@code "OU"}) and
     * leave the measurement-name out — typically because they expect
     * the laterality column header to carry the eye context and the
     * row label to carry the measurement name, then forget to populate
     * one of them. The fallback chain rescues the resulting per-side
     * row by deriving a sensible label:
     *
     * <ol>
     *   <li>If the operator typed a real label (not just an eye
     *       marker) — pass through unchanged.</li>
     *   <li>Otherwise fall back to the operator-entered
     *       {@code descriptionLabel} (the wizard's required field), or
     *       the item {@code name} if even descriptionLabel is blank.</li>
     *   <li>If a laterality marker is detectable from the item's
     *       {@code name}/{@code oid} tokens — or was typed verbatim
     *       into {@code leftItemText} — append it in parentheses
     *       ({@code "BCVA letters (OD)"}) so per-side rows still read
     *       laterally without losing the measurement-name.</li>
     * </ol>
     */
    static String synthesizeLeftItemText(CrfVersionAuthoringRequest.Item item) {
        String left = item.leftItemText();
        boolean leftIsBlank = left == null || left.trim().isEmpty();
        boolean leftIsJustEyeMarker = !leftIsBlank && isJustEyeMarker(left);

        if (!leftIsBlank && !leftIsJustEyeMarker) {
            return left;
        }

        String base = firstNonBlank(item.descriptionLabel(), item.name());
        if (base == null) {
            // Nothing to synthesise from — return whatever was there
            // (likely an empty string) so the parser sees an unchanged
            // cell and decides for itself whether to reject the row.
            return nullSafe(left);
        }
        String laterality = detectLaterality(item);
        if (laterality != null) {
            return base + " (" + laterality + ")";
        }
        return base;
    }

    /**
     * Synthesise the {@code RIGHT_ITEM_TEXT} cell — the helper hint
     * shown below the input widget (usually a unit like {@code "mmHg"}
     * or a range hint like {@code "letters (0-100)"}). When the
     * operator leaves it blank, fall back to the {@code units} field so
     * the renderer still surfaces a unit hint. Mirrors the read-side
     * fallback chain {@code EventCrfsApiController.buildItemDto}
     * already applies.
     */
    static String synthesizeRightItemText(CrfVersionAuthoringRequest.Item item) {
        String right = item.rightItemText();
        if (right != null && !right.trim().isEmpty()) {
            return right;
        }
        return nullSafe(item.units());
    }

    /**
     * True if the string, ignoring case + surrounding whitespace, is
     * exactly an eye marker token ({@code "OD"}, {@code "OS"},
     * {@code "OU"}) and nothing else.
     */
    private static boolean isJustEyeMarker(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.equalsIgnoreCase("OD") || t.equalsIgnoreCase("OS") || t.equalsIgnoreCase("OU");
    }

    /**
     * Detect the laterality marker for an item by scanning its
     * underscore-delimited {@code name} and {@code oid} tokens for an
     * exact {@code OD}/{@code OS}/{@code OU} match. Falls back to an
     * eye-marker-only {@code leftItemText} as a third source.
     * Returns {@code null} if no marker is detectable.
     *
     * <p>Mirrors the SPA-side {@code parseEyePrefix} token scan in
     * {@code components/bilateral.ts} so the synthesised label tracks
     * the same laterality the bilateral row grouper detects.
     */
    private static String detectLaterality(CrfVersionAuthoringRequest.Item item) {
        for (String source : List.of(nullSafe(item.name()), nullSafe(item.oid()))) {
            if (source.isEmpty()) continue;
            for (String token : source.split("_")) {
                String up = token.toUpperCase(Locale.ROOT);
                if (up.equals("OD") || up.equals("OS") || up.equals("OU")) {
                    return up;
                }
            }
        }
        String left = item.leftItemText();
        if (left != null && isJustEyeMarker(left)) {
            return left.trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** First non-blank string, or {@code null}. */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c;
        }
        return null;
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
