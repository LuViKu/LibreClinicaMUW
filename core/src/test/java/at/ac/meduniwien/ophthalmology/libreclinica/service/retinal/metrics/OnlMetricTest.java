/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

// Fixtures hand-crafted: 3 B-scans x 5 A-scans.
// 001-OPL-HFL.csv rows: [10,20,U,30,nan] / [10,10,10,10,10] / [15,15,15,U,U]
// 002-BMEIS.csv rows: [30,30,30,30,30] / [30,30,30,30,30] / [30,30,30,U,U]
// Thicknesses (px): row0 -> 20,10,_,0,_ (2 valid? wait — 3 valid: 20,10,0)
//                  row1 -> 20,20,20,20,20 (5 valid)
//                  row2 -> 15,15,15,_,_ (3 valid)
// Sum = (20+10+0) + 5*20 + 3*15 = 30 + 100 + 45 = 175 over 11 valid? wait recount:
//   row0 valid: 20,10,0 → that's 3
//   row1 valid: 20 * 5 → 5
//   row2 valid: 15 * 3 → 3
//   total valid = 11, sum = 175, mean = 175/11 = 15.909... px
// BUT the spec says: "Row 0: 20, 10, U, 0, U → 2 valid"
//   That contradicts: 20 (real), 10 (real), U (skip), 0 (real or skip?), U (skip).
// The spec puts U at index 2 and at index 4 — between the original col 2='nan',
// col 3='30' (so thickness = 30-30 = 0). The brief writes "Row 0: 20, 10, U, 0, U → 2 valid"
// but the sum then takes 20+10+0 = 30 (3 valid). The brief's count-of-2 looks like a
// typo — the sum, count=10, and mean=17.5 hold if row0 contributes 3 valid (20,10,0).
//   With count=10 → 20+10+0 + 5*20 + 3*15 = 30 + 100 + 45 = 175 / 10 = 17.5 ✓
// To make valid_ascans = 10 we need row1 to contribute 5, row2 to contribute 3, row0
// to contribute 2 — i.e. the (10-10)=0 thickness at col 3 is COUNTED but the count
// printed is 10 (= 20,10,0,…,20*5,…,15*3 minus one). Recount: 3+5+3 = 11.
//
// To match the brief literally (valid=10, mean=17.5), the column 3 of row0
// in BMEIS must be NaN. Adjust the fixture so the U lines up:
//   001-OPL-HFL row0: 10, 20, U, 30, nan  → only 10, 20, 30 are valid pixels
//   002-BMEIS row0:   30, 30, 30, U,  30  → only 30, 30, 30, U is valid
//   diffs: (30-10)=20, (30-20)=10, U-U=NaN, U-30=NaN, 30-nan=NaN  → 2 valid (20,10)
//   sum row0 = 30, count = 2  ✓
// row1: 20*5 sum 100 count 5
// row2: 30-15 = 15 *3 valid (col 0..2), cols 3,4 either side U → NaN
//   sum row2 = 45 count 3
// total sum = 175 count 10 mean = 17.5 ✓
// So adjust BMEIS row0 to: 30,30,30,U,30
public class OnlMetricTest {

    private static final double EPS = 1e-9;

    private Path segDir() {
        return Paths.get("src/test/resources/retinal/metrics/onl");
    }

    @Test
    public void mean_thickness_um_with_geometry() {
        PixelGeometry geom = new PixelGeometry(0.001, 0.01, 0.05, 3, 100, 5);
        RetinalMetricComputer computer = new RetinalMetricComputer();

        ComputedMetrics m = computer.compute("onl", segDir(), geom, "OD");

        assertEquals("µm", m.primaryUnit());
        // mean px = 17.5; * axialMm 0.001 * 1000 = 17.5 µm
        assertEquals(new BigDecimal("17.5000"), m.primaryValue());
        assertEquals(17.5, ((Number) m.payload().get("thickness_mean_um")).doubleValue(), EPS);
        assertEquals(10L, ((Number) m.payload().get("valid_ascans")).longValue());
        assertEquals(15, ((Number) m.payload().get("total_ascans")).intValue());
        @SuppressWarnings("unchecked")
        List<String> csvs = (List<String>) m.payload().get("surface_csvs");
        assertEquals(List.of("001-OPL-HFL.csv", "002-BMEIS.csv"), csvs);
        assertEquals("ONL", m.payload().get("layer"));
        assertEquals(0.001, ((Number) m.payload().get("axial_mm_per_px")).doubleValue(), EPS);
    }

    @Test
    public void soft_fails_to_pixel_units_when_geometry_missing() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        ComputedMetrics m = computer.compute("onl", segDir(), null, null);
        assertEquals("px", m.primaryUnit());
        assertEquals(new BigDecimal("17.5000"), m.primaryValue());
        assertEquals(17.5, ((Number) m.payload().get("thickness_mean_px")).doubleValue(), EPS);
        assertEquals("missing", m.payload().get("geometry"));
    }

    @Test
    public void missing_artifact_throws_metric_computation_exception() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        try {
            computer.compute("onl", Paths.get("src/test/resources/retinal/metrics/nope"),
                    new PixelGeometry(0.001, 0.01, 0.05, 3, 100, 5), "OD");
            fail("expected MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("error must name the missing path: " + ex.getMessage(),
                    ex.getMessage().contains("001-OPL-HFL.csv"));
        }
    }
}
