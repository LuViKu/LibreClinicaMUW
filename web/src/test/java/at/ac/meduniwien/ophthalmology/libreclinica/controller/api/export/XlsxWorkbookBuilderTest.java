/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Phase E.6 — verify the XSSF audit-export builder round-trips
 * through POI cleanly: bold header, freeze pane, data rows, sheet
 * name. The audit-log endpoint depends on every one of these
 * properties for the sponsor / inspector hand-off contract.
 */
class XlsxWorkbookBuilderTest {

    @Test
    void roundTripsHeaderAndDataRows() throws Exception {
        byte[] xlsx;
        try (XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("Audit log")) {
            b.writeHeader("When", "Who", "What");
            b.writeRow("2026-06-06T08:00:00Z", "monitor_demo", "Signed");
            b.writeRow("2026-06-06T08:05:00Z", "investigator_demo", "Reviewed");
            b.autoSize();
            xlsx = b.toByteArray();
        }
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet s = wb.getSheetAt(0);
            assertEquals("Audit log", s.getSheetName());
            // Header + 2 data rows.
            assertEquals(3, s.getPhysicalNumberOfRows());
            Row header = s.getRow(0);
            assertEquals("When", header.getCell(0).getStringCellValue());
            assertEquals("Who", header.getCell(1).getStringCellValue());
            assertEquals("What", header.getCell(2).getStringCellValue());
            // Header style — bold.
            assertTrue(wb.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold());
            // Freeze pane: top row pinned.
            assertEquals(1, s.getPaneInformation().getHorizontalSplitPosition());
            // Data row content.
            Row d1 = s.getRow(1);
            assertEquals("monitor_demo", d1.getCell(1).getStringCellValue());
            Row d2 = s.getRow(2);
            assertEquals("Reviewed", d2.getCell(2).getStringCellValue());
        }
    }

    @Test
    void writeRowAfterHeaderRejectsLateHeaderCall() throws Exception {
        try (XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("S")) {
            b.writeHeader("a");
            b.writeRow("1");
            assertThrows(IllegalStateException.class, () -> b.writeHeader("oops"));
        }
    }

    @Test
    void writesUmlautsVerbatim() throws Exception {
        byte[] xlsx;
        try (XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("Audit")) {
            b.writeHeader("title");
            b.writeRow("Östrogen-Würmer-Bericht");
            xlsx = b.toByteArray();
        }
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            assertEquals("Östrogen-Würmer-Bericht",
                    s.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void countsDataRowsExclusiveOfHeader() throws Exception {
        try (XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("S")) {
            assertEquals(0, b.dataRowCount());
            b.writeHeader("a");
            assertEquals(0, b.dataRowCount());
            b.writeRow("1");
            b.writeRow("2");
            assertEquals(2, b.dataRowCount());
        }
    }
}
