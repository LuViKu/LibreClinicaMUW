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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;

/**
 * Phase E.6 Milestone B — pins the JSON → repeating-template workbook
 * adapter at the cell level. The {@code SpreadSheetTableRepeating}
 * parser reads cells by short index ({@link
 * at.ac.meduniwien.ophthalmology.libreclinica.core.util.CrfTemplateColumnNameEnum});
 * if we shift a column the parser silently misinterprets the row.
 * These tests fail the build before the parser does.
 *
 * <p>The legacy POI 3.x {@link HSSFWorkbook} pre-dates
 * {@link java.lang.AutoCloseable} so we manage workbook lifecycles
 * manually here — there's no resource to release beyond the
 * underlying byte buffer, which is GC-managed.
 */
class CrfJsonToWorkbookAdapterTest {

    private final CrfJsonToWorkbookAdapter adapter = new CrfJsonToWorkbookAdapter();
    private Path generated;

    @AfterEach
    void cleanup() throws Exception {
        if (generated != null) {
            Files.deleteIfExists(generated);
        }
    }

    private static CRFBean crf(String name) {
        CRFBean b = new CRFBean();
        b.setName(name);
        return b;
    }

    private HSSFSheet sheet(HSSFWorkbook wb, String name) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (wb.getSheetName(i).equalsIgnoreCase(name)) return wb.getSheetAt(i);
        }
        throw new AssertionError("missing sheet '" + name + "'");
    }

    private String str(HSSFRow row, int col) {
        HSSFCell c = row.getCell((short) col);
        return c == null ? null : c.getStringCellValue();
    }

    private double num(HSSFRow row, int col) {
        HSSFCell c = row.getCell((short) col);
        return c == null ? Double.NaN : c.getNumericCellValue();
    }

    private HSSFWorkbook synthesize(CrfVersionAuthoringRequest req) throws Exception {
        generated = adapter.synthesize(req, crf("Demographics"));
        try (FileInputStream in = new FileInputStream(generated.toFile())) {
            return new HSSFWorkbook(in);
        }
    }

    /**
     * Builder helper — keeps the verbose 17-arg {@link
     * CrfVersionAuthoringRequest.Item} constructor calls compact and
     * insulates the test suite from M-D / M-E record-shape evolutions.
     * Defaults match the minimal-fill-out path: blank optional strings,
     * not required, no response set, no validation, no M-C extensions.
     */
    private static CrfVersionAuthoringRequest.Item item(
            String name, String descriptionLabel, String leftItemText,
            String units, String rightItemText, String dataType,
            String defaultValue, boolean required,
            CrfVersionAuthoringRequest.ResponseSet responseSet,
            CrfVersionAuthoringRequest.Validation validation) {
        return new CrfVersionAuthoringRequest.Item(
                name, "", descriptionLabel, leftItemText, rightItemText, units,
                dataType, defaultValue, required, responseSet, validation,
                null, null, null, null, false, null);
    }

    private static CrfVersionAuthoringRequest.Item simpleItem(
            String name, String dataType,
            CrfVersionAuthoringRequest.ResponseSet rs,
            CrfVersionAuthoringRequest.Validation val) {
        return item(name, name, "", "", "", dataType, "", false, rs, val);
    }

    private static CrfVersionAuthoringRequest.Item simpleItem(String name, String dataType) {
        return simpleItem(name, dataType, null, null);
    }

    /* ----------------------------------------------------------------- */
    /* Sheet topology                                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void synthesisedWorkbookHasFiveSheetsInOrder() throws Exception {
        // The legacy repeating parser reads wb.getSheetAt(4) for the
        // version-number row, so the adapter emits an Instructions
        // sheet at slot 4 in addition to CRF / Sections / Groups /
        // Items. Pin the order so a future refactor can't silently
        // misalign with the parser's positional lookup.
        var item = simpleItem("AGE", "INTEGER");
        var section = new CrfVersionAuthoringRequest.Section("S1", "Sec 1", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        assertEquals(5, wb.getNumberOfSheets());
        assertEquals("CRF", wb.getSheetName(0));
        assertEquals("Sections", wb.getSheetName(1));
        assertEquals("Groups", wb.getSheetName(2));
        assertEquals("Items", wb.getSheetName(3));
        assertEquals("Instructions", wb.getSheetName(4));
    }

    @Test
    void groupsSheetPresenceFlipsParserIntoRepeatingMode() throws Exception {
        // The parser detects "repeating" by the presence of a Groups
        // sheet (see SpreadSheetTableRepeating constructor). Pin that we
        // always emit it — even when the operator hasn't authored any
        // explicit groups.
        var item = simpleItem("X", "ST");
        var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFSheet groups = sheet(wb, "Groups");
        assertNotNull(groups);
    }

    /* ----------------------------------------------------------------- */
    /* Items sheet — column mapping                                      */
    /* ----------------------------------------------------------------- */

    @Test
    void itemRowSitsAtTheRepeatingTemplateColumnIndices() throws Exception {
        // Pin the cell layout the repeating parser reads. Shifting any
        // column reorders the parser's interpretation of the row.
        // Record field order is (name, oid, descriptionLabel, leftItemText,
        // rightItemText, units, dataType, defaultValue, required, …).
        // The XLS canonical column order has UNITS at col 3 and
        // RIGHT_ITEM_TEXT at col 4, so the constructor positions for
        // rightItemText + units differ from the column positions —
        // pin "completed" → rightItemText (col 4) and "yrs" → units (col 3).
        var item = item("AGE", "Age in years", "Years",
                "yrs", "completed", "INTEGER", "0",
                true, null, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "Demographics", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFSheet items = sheet(wb, "Items");
        HSSFRow row = items.getRow(1);

        assertEquals("AGE", str(row, 0));               // ITEM_NAME
        assertEquals("Age in years", str(row, 1));      // DESCRIPTION_LABEL
        assertEquals("Years", str(row, 2));             // LEFT_ITEM_TEXT
        assertEquals("yrs", str(row, 3));               // UNITS
        assertEquals("completed", str(row, 4));         // RIGHT_ITEM_TEXT
        assertEquals("S1", str(row, 5));                // SECTION_LABEL
        assertEquals("", str(row, 6));                  // GROUP_LABEL (M-C)
        assertEquals("text", str(row, 13));             // RESPONSE_TYPE default
        assertEquals("0", str(row, 18));                // DEFAULT_VALUE
        assertEquals("INT", str(row, 19));              // DATA_TYPE canonical
        assertEquals(0.0d, num(row, 23));               // PHI numeric
        assertEquals(1.0d, num(row, 24));               // REQUIRED numeric
    }

    @Test
    void radioResponseSetExpandsToFourCells() throws Exception {
        // The repeating parser reads RESPONSE_TYPE (13), RESPONSE_LABEL
        // (14), RESPONSE_OPTIONS_TEXT (15), RESPONSE_VALUES (16). Make
        // sure inline options land at those exact indices.
        var rs = new CrfVersionAuthoringRequest.ResponseSet("radio", "yes_no",
                List.of(new CrfVersionAuthoringRequest.Option("Yes", "1"),
                        new CrfVersionAuthoringRequest.Option("No", "0")),
                null);
        var item = simpleItem("SMOKER", "ST", rs, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "Items").getRow(1);
        assertEquals("radio", str(row, 13));
        assertEquals("yes_no", str(row, 14));
        assertEquals("Yes,No", str(row, 15));
        assertEquals("1,0", str(row, 16));
    }

    @Test
    void validationRegexpWrappedInLegacyClause() throws Exception {
        var val = new CrfVersionAuthoringRequest.Validation("[0-9]+", "Digits only");
        var item = new CrfVersionAuthoringRequest.Item(
                "PHONE", "", "Phone", "", "", "", "ST", "", false, null, val,
                null, null, null, null, false, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "Items").getRow(1);
        assertEquals("regexp: /[0-9]+/", str(row, 21));
        assertEquals("Digits only", str(row, 22));
    }

    @Test
    void everyDataTypeCanonicalisesCorrectly() throws Exception {
        // Long names map to short codes the parser's ItemDataType
        // resolver understands.
        java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("INTEGER", "INT");
        expected.put("BOOLEAN", "BL");
        expected.put("REAL", "REAL");
        expected.put("DATE", "DATE");
        expected.put("ST", "ST");

        for (var entry : expected.entrySet()) {
            var item = new CrfVersionAuthoringRequest.Item(
                    "X_" + entry.getValue(), "", "X", "", "", "", entry.getKey(), "", false, null, null,
                    null, null, null, null, false, null);
            var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
            var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));
            HSSFWorkbook wb = synthesize(req);
            HSSFRow row = sheet(wb, "Items").getRow(1);
            assertEquals(entry.getValue(), str(row, 19),
                    "data type '" + entry.getKey() + "' should canonicalise to '"
                            + entry.getValue() + "'");
        }
    }

    @Test
    void sectionsSheetCarriesEachAuthoredSection() throws Exception {
        var i1 = new CrfVersionAuthoringRequest.Item("A", "", "A", "", "", "", "ST", "", false, null, null,
                null, null, null, null, false, null);
        var i2 = new CrfVersionAuthoringRequest.Item("B", "", "B", "", "", "", "ST", "", false, null, null,
                null, null, null, null, false, null);
        var s1 = new CrfVersionAuthoringRequest.Section("S1", "Sec 1", "Instructions 1", 1, List.of(i1));
        var s2 = new CrfVersionAuthoringRequest.Section("S2", "Sec 2", "", 2, List.of(i2));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(s1, s2));

        HSSFWorkbook wb = synthesize(req);
        HSSFSheet sections = sheet(wb, "Sections");
        assertEquals("S1", str(sections.getRow(1), 0));
        assertEquals("Sec 1", str(sections.getRow(1), 1));
        assertEquals("Instructions 1", str(sections.getRow(1), 3));
        assertEquals("S2", str(sections.getRow(2), 0));
    }

    @Test
    void crfSheetCarriesParentCrfName() throws Exception {
        var item = new CrfVersionAuthoringRequest.Item(
                "AGE", "", "Age", "", "", "", "INTEGER", "", false, null, null,
                null, null, null, null, false, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v2.5", "Demo CRF", "Initial release",
                List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "CRF").getRow(1);
        assertEquals("Demographics", str(row, 0));
        assertEquals("v2.5", str(row, 1));
        assertEquals("Demo CRF", str(row, 2));
        assertTrue(str(row, 3).contains("Initial release"));
    }

    /* ----------------------------------------------------------------- */
    /* M-C — show-when conditional display                                */
    /* ----------------------------------------------------------------- */

    @Test
    void showItemRowEmitsHideStatusAndConditionalDisplayTriple() throws Exception {
        // M-C — when an item carries showItem, the adapter writes
        // ITEM_DISPLAY_STATUS = "Hide" (col 25), the parentItemOid
        // (col 9 = PARENT_ITEM) and the SIMPLE_CONDITIONAL_DISPLAY
        // triple at col 26 (parentOid,parentValue,message).
        var i1 = new CrfVersionAuthoringRequest.Item(
                "AGE", "AGE", "Age", "", "", "", "INTEGER", "", false, null, null,
                null, null, null, null, false, null);
        var i2 = new CrfVersionAuthoringRequest.Item(
                "PEDIATRIC_NOTES", "PEDIATRIC_NOTES", "Pediatric notes", "", "", "",
                "ST", "", false, null, null,
                "1|Show only for adults", "AGE", null, null, false, null);
        var section = new CrfVersionAuthoringRequest.Section(
                "S1", "Demographics", "", 1, List.of(i1, i2));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFSheet items = sheet(wb, "Items");
        HSSFRow childRow = items.getRow(2);
        assertEquals("AGE", str(childRow, 9));                  // PARENT_ITEM
        assertEquals("Hide", str(childRow, 25));                // ITEM_DISPLAY_STATUS
        assertEquals("AGE,1,Show only for adults", str(childRow, 26)); // SIMPLE_CONDITIONAL_DISPLAY
    }

    @Test
    void itemWithoutShowItemHasBlankDisplayCells() throws Exception {
        var item = new CrfVersionAuthoringRequest.Item(
                "AGE", "AGE", "Age", "", "", "", "INTEGER", "", false, null, null,
                null, null, null, null, false, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "S", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "Items").getRow(1);
        assertEquals("", str(row, 9));   // PARENT_ITEM blank when no showItem
        assertEquals("", str(row, 25));  // ITEM_DISPLAY_STATUS blank
        assertEquals("", str(row, 26));  // SIMPLE_CONDITIONAL_DISPLAY blank
    }

    /* ----------------------------------------------------------------- */
    /* M-C — calculation response types                                   */
    /* ----------------------------------------------------------------- */

    @Test
    void calculationResponseSetCarriesFormulaInCols15And16() throws Exception {
        // M-C — calculation variants land the formula in
        // RESPONSE_OPTIONS_TEXT (15) and RESPONSE_VALUES_OR_CALCS (16).
        var rs = new CrfVersionAuthoringRequest.ResponseSet("calculation", "bmi_calc",
                List.of(new CrfVersionAuthoringRequest.Option("WEIGHT / (HEIGHT * HEIGHT)",
                        "WEIGHT / (HEIGHT * HEIGHT)")),
                null);
        var item = new CrfVersionAuthoringRequest.Item(
                "BMI", "BMI", "BMI", "", "", "", "REAL", "", false, rs, null,
                null, null, null, null, false, null);
        var section = new CrfVersionAuthoringRequest.Section(
                "S1", "Vitals", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "Items").getRow(1);
        assertEquals("calculation", str(row, 13));
        assertEquals("bmi_calc", str(row, 14));
        assertEquals("WEIGHT / (HEIGHT * HEIGHT)", str(row, 15));
        assertEquals("WEIGHT / (HEIGHT * HEIGHT)", str(row, 16));
    }

    /* ----------------------------------------------------------------- */
    /* M-C — header / subHeader / pageBreak                              */
    /* ----------------------------------------------------------------- */

    @Test
    void headerSubHeaderAndPageBreakRowsLandAtCanonicalColumns() throws Exception {
        var item = new CrfVersionAuthoringRequest.Item(
                "BP_SYS", "BP_SYS", "Systolic BP", "", "", "mmHg", "INT", "", false,
                null, null, null, null,
                "Vital signs", "Cardiovascular", true, null);
        var section = new CrfVersionAuthoringRequest.Section(
                "S1", "Vitals", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        HSSFRow row = sheet(wb, "Items").getRow(1);
        assertEquals("Vital signs", str(row, 7));      // HEADER
        assertEquals("Cardiovascular", str(row, 8));   // SUBHEADER
        assertEquals("page-break", str(row, 17));      // RESPONSE_LAYOUT — page break
    }

    /* ----------------------------------------------------------------- */
    /* M-C — flat repeating item groups                                  */
    /* ----------------------------------------------------------------- */

    @Test
    void distinctGroupLabelsLandOnGroupsSheet() throws Exception {
        var i1 = new CrfVersionAuthoringRequest.Item(
                "DRUG_NAME", "DRUG_NAME", "Drug", "", "", "", "ST", "", false, null, null,
                null, null, null, null, false, "MEDS");
        var i2 = new CrfVersionAuthoringRequest.Item(
                "DRUG_DOSE", "DRUG_DOSE", "Dose", "", "", "", "REAL", "", false, null, null,
                null, null, null, null, false, "MEDS");
        var i3 = new CrfVersionAuthoringRequest.Item(
                "AE_NAME", "AE_NAME", "AE", "", "", "", "ST", "", false, null, null,
                null, null, null, null, false, "ADVERSE");
        var s1 = new CrfVersionAuthoringRequest.Section(
                "S1", "Meds", "", 1, List.of(i1, i2));
        var s2 = new CrfVersionAuthoringRequest.Section(
                "S2", "AEs", "", 2, List.of(i3));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(s1, s2));

        HSSFWorkbook wb = synthesize(req);
        HSSFSheet groups = sheet(wb, "Groups");
        // header at row 0; one row per distinct label in first-seen order
        assertEquals("MEDS", str(groups.getRow(1), 0));
        assertEquals("grid", str(groups.getRow(1), 1));
        assertEquals("1", str(groups.getRow(1), 3));
        assertEquals("40", str(groups.getRow(1), 4));
        assertEquals("ADVERSE", str(groups.getRow(2), 0));
        assertEquals("grid", str(groups.getRow(2), 1));
        // and the items carry the same group label on col 6
        HSSFSheet items = sheet(wb, "Items");
        assertEquals("MEDS", str(items.getRow(1), 6));
        assertEquals("MEDS", str(items.getRow(2), 6));
        assertEquals("ADVERSE", str(items.getRow(3), 6));
    }

    /* ----------------------------------------------------------------- */
    /* LEFT_ITEM_TEXT / RIGHT_ITEM_TEXT synthesis fallback               */
    /* ----------------------------------------------------------------- */

    private static CrfVersionAuthoringRequest.Item itemWithOid(
            String name, String oid, String descriptionLabel,
            String leftItemText, String units, String rightItemText) {
        return new CrfVersionAuthoringRequest.Item(
                name, oid, descriptionLabel, leftItemText, rightItemText, units,
                "INTEGER", "", false, null, null,
                null, null, null, null, false, null);
    }

    @Test
    void synthesizeLeftItemText_passesThroughOperatorProvidedLabel() {
        var item = itemWithOid("VA_OD", "I_OPHTH_OD_BCVA",
                "BCVA letters", "Right eye — ETDRS letters", "letters", "");
        assertEquals("Right eye — ETDRS letters",
                CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeLeftItemText_replacesEyeMarkerOnlyLabelWithDescription_plusLaterality() {
        // The wizard's CRF Library bug: the operator typed only "OD"
        // for the laterality marker and left the measurement name
        // unspecified. Synthesis fills in the description-label and
        // wraps it with the eye marker detected from the OID tokens.
        var item = itemWithOid("OD_BCVA_LETTERS", "I_OPHTH_OD_BCVA_LETTERS",
                "BCVA letters", "OD", "letters", "");
        assertEquals("BCVA letters (OD)",
                CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeLeftItemText_handlesBlankLeftItemTextWithLateralityInOid() {
        var item = itemWithOid("OS_IOP", "I_IOP_OS",
                "Intraocular pressure", "", "mmHg", "");
        assertEquals("Intraocular pressure (OS)",
                CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeLeftItemText_fallsBackToNameWhenDescriptionLabelIsBlank() {
        var item = itemWithOid("OD_BCVA", "I_VA_OD_ETDRS", "", "OD", "letters", "");
        assertEquals("OD_BCVA (OD)",
                CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeLeftItemText_returnsEmptyStringWhenNoSourcesAvailable() {
        var item = itemWithOid("", "", "", "", "", "");
        assertEquals("", CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeLeftItemText_skipsLateralityWrapperWhenOidHasNoEyeToken() {
        // Non-ophth items have no laterality — synthesis should just
        // return the description-label without the eye-marker suffix.
        var item = itemWithOid("AGE", "I_AGE", "Age at enrollment", "", "years", "");
        assertEquals("Age at enrollment",
                CrfJsonToWorkbookAdapter.synthesizeLeftItemText(item));
    }

    @Test
    void synthesizeRightItemText_passesThroughOperatorProvidedHint() {
        var item = itemWithOid("VA", "I_VA",
                "BCVA letters", "BCVA letters", "letters", "letters (0-100)");
        assertEquals("letters (0-100)",
                CrfJsonToWorkbookAdapter.synthesizeRightItemText(item));
    }

    @Test
    void synthesizeRightItemText_fallsBackToUnitsWhenBlank() {
        var item = itemWithOid("VA", "I_VA",
                "BCVA letters", "BCVA letters", "mmHg", "");
        assertEquals("mmHg",
                CrfJsonToWorkbookAdapter.synthesizeRightItemText(item));
    }

    @Test
    void synthesizeRightItemText_returnsEmptyWhenBothBlank() {
        var item = itemWithOid("X", "I_X", "X", "X label", "", "");
        assertEquals("", CrfJsonToWorkbookAdapter.synthesizeRightItemText(item));
    }

    /* ----------------------------------------------------------------- */
    /* Catalog materialization (F3)                                      */
    /* ----------------------------------------------------------------- */

    private static CrfVersionAuthoringRequest.Item catalogItem(String name, String oid, String catalogCode) {
        return new CrfVersionAuthoringRequest.Item(
                name, oid,
                /* descriptionLabel */ null,
                /* leftItemText     */ null,
                /* rightItemText    */ null,
                /* units            */ null,
                /* dataType         */ null,
                /* defaultValue     */ "",
                /* required         */ false,
                /* responseSet      */ null,
                /* validation       */ null,
                /* showItem         */ null,
                /* parentItemOid    */ null,
                /* header           */ null,
                /* subHeader        */ null,
                /* pageBreak        */ false,
                /* groupLabel       */ null,
                /* catalogCode      */ catalogCode);
    }

    private static java.util.Map<String, CrfJsonToWorkbookAdapter.CatalogRow> miniCatalog() {
        var iop = new CrfJsonToWorkbookAdapter.CatalogRow(
                "IOP", "Augeninnendruck (IOP)", "mmHg", "integer", "number-stepper",
                null, null, java.util.List.of());
        var done = new CrfJsonToWorkbookAdapter.CatalogRow(
                "SPECTRALIS_OCT_DONE", "Spectralis-OCT durchgeführt", null, "select-one", "yesno",
                null, null,
                java.util.List.of(
                        new CrfJsonToWorkbookAdapter.CatalogResponseOption("ja", "Ja"),
                        new CrfJsonToWorkbookAdapter.CatalogResponseOption("nein", "Nein")));
        var reason = new CrfJsonToWorkbookAdapter.CatalogRow(
                "SPECTRALIS_OCT_REASON", "Spectralis-OCT — Grund falls nicht durchgeführt",
                null, "string", "text",
                "SPECTRALIS_OCT_DONE", "nein",
                java.util.List.of());
        return java.util.Map.of(
                "IOP", iop,
                "SPECTRALIS_OCT_DONE", done,
                "SPECTRALIS_OCT_REASON", reason);
    }

    @Test
    void materializeItem_passesThroughWhenNoCatalogCodeSet() {
        var item = new CrfVersionAuthoringRequest.Item(
                "AGE", "I_AGE", "Age", "Age", "", "yrs",
                "INTEGER", "", false, null, null,
                null, null, null, null, false, null);
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());
        assertEquals(item, out);
    }

    @Test
    void materializeItem_passesThroughWhenCatalogCodeUnknown() {
        var item = catalogItem("X", "I_OPHTH_OD_X", "DOES_NOT_EXIST");
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());
        assertEquals(item, out);
    }

    @Test
    void materializeItem_fillsBlankFieldsFromCatalogForNumericEntry() {
        // The wizard sends only the catalog code + OID; everything else
        // back-fills from the catalog row.
        var item = catalogItem("OD_IOP", "I_OPHTH_OD_IOP", "IOP");
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());

        assertEquals("Augeninnendruck (IOP)", out.descriptionLabel());
        assertEquals("Augeninnendruck (IOP)", out.leftItemText());
        assertEquals("mmHg", out.rightItemText());
        assertEquals("mmHg", out.units());
        assertEquals("integer", out.dataType());
        // Catalog code pass-through so the downstream layer (validator,
        // audit) can still inspect it.
        assertEquals("IOP", out.catalogCode());
        // No response options on a number-stepper.
        assertEquals(null, out.responseSet());
    }

    @Test
    void materializeItem_authoredFieldsBeatCatalogDefaults() {
        // Operator typed a custom label — catalog must NOT overwrite it.
        var item = new CrfVersionAuthoringRequest.Item(
                "OD_IOP", "I_OPHTH_OD_IOP",
                /* descriptionLabel */ "Custom IOP label",
                /* leftItemText     */ null,
                /* rightItemText    */ null,
                /* units            */ null,
                /* dataType         */ null,
                "", false, null, null,
                null, null, null, null, false, null, "IOP");
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());
        assertEquals("Custom IOP label", out.descriptionLabel());
        // Other blank fields still fill from catalog.
        assertEquals("Augeninnendruck (IOP)", out.leftItemText());
        assertEquals("mmHg", out.units());
    }

    @Test
    void materializeItem_synthesizesResponseSetFromCatalogYesNoOptions() {
        var item = catalogItem("OD_SPECTRALIS", "I_OPHTH_OD_SPECTRALIS_OCT_DONE", "SPECTRALIS_OCT_DONE");
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());

        assertNotNull(out.responseSet());
        assertEquals("radio", out.responseSet().type());
        assertEquals(2, out.responseSet().options().size());
        // Order: (text, value) — text first per the record's contract.
        assertEquals("Ja", out.responseSet().options().get(0).text());
        assertEquals("ja", out.responseSet().options().get(0).value());
        assertEquals("Nein", out.responseSet().options().get(1).text());
        assertEquals("nein", out.responseSet().options().get(1).value());
    }

    @Test
    void materializeItem_emitsShowItemTripleWhenCatalogDeclaresConditional() {
        var item = catalogItem("OD_REASON", "I_OPHTH_OD_SPECTRALIS_OCT_REASON", "SPECTRALIS_OCT_REASON");
        var out = CrfJsonToWorkbookAdapter.materializeItem(item, miniCatalog());
        // Format: "{showWhenValue}|{conditionalOnCode} == {showWhenValue}".
        assertEquals("nein|SPECTRALIS_OCT_DONE == nein", out.showItem());
    }

    /* ----------------------------------------------------------------- */
    /* parseResponseOptions storage format                               */
    /* ----------------------------------------------------------------- */

    @Test
    void parseResponseOptions_canonicalPipePair() {
        var opts = CrfJsonToWorkbookAdapter.parseResponseOptions("ja|Ja,nein|Nein");
        assertEquals(2, opts.size());
        assertEquals("ja", opts.get(0).value);
        assertEquals("Ja", opts.get(0).label);
        assertEquals("nein", opts.get(1).value);
        assertEquals("Nein", opts.get(1).label);
    }

    @Test
    void parseResponseOptions_handlesNullAndBlankInput() {
        assertEquals(0, CrfJsonToWorkbookAdapter.parseResponseOptions(null).size());
        assertEquals(0, CrfJsonToWorkbookAdapter.parseResponseOptions("").size());
        assertEquals(0, CrfJsonToWorkbookAdapter.parseResponseOptions("   ").size());
    }
}
