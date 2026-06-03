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

    /* ----------------------------------------------------------------- */
    /* Sheet topology                                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void synthesisedWorkbookHasFourSheetsInOrder() throws Exception {
        var item = new CrfVersionAuthoringRequest.Item(
                "AGE", "", "Age", "", "", "", "INTEGER", "", true, null, null);
        var section = new CrfVersionAuthoringRequest.Section("S1", "Sec 1", "", 1, List.of(item));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));

        HSSFWorkbook wb = synthesize(req);
        assertEquals(4, wb.getNumberOfSheets());
        assertEquals("CRF", wb.getSheetName(0));
        assertEquals("Sections", wb.getSheetName(1));
        assertEquals("Groups", wb.getSheetName(2));
        assertEquals("Items", wb.getSheetName(3));
    }

    @Test
    void groupsSheetPresenceFlipsParserIntoRepeatingMode() throws Exception {
        // The parser detects "repeating" by the presence of a Groups
        // sheet (see SpreadSheetTableRepeating constructor). Pin that we
        // always emit it — even when the operator hasn't authored any
        // explicit groups.
        var item = new CrfVersionAuthoringRequest.Item(
                "X", "", "X", "", "", "", "ST", "", false, null, null);
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
        var item = new CrfVersionAuthoringRequest.Item(
                "AGE", "", "Age in years", "Years",
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
        var item = new CrfVersionAuthoringRequest.Item(
                "SMOKER", "", "Smoker", "", "", "", "ST", "", false, rs, null);
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
                "PHONE", "", "Phone", "", "", "", "ST", "", false, null, val);
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
                    "X_" + entry.getValue(), "", "X", "", "", "", entry.getKey(), "", false, null, null);
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
        var i1 = new CrfVersionAuthoringRequest.Item("A", "", "A", "", "", "", "ST", "", false, null, null);
        var i2 = new CrfVersionAuthoringRequest.Item("B", "", "B", "", "", "", "ST", "", false, null, null);
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
                "AGE", "", "Age", "", "", "", "INTEGER", "", false, null, null);
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
}
