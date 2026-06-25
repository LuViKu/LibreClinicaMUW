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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

// Fixture 001-RPEL.csv (3 B-scans x 5 A-scans):
//   row 0: 0,1,0,1,U  → 2 hot pixels at x=1, x=3
//   row 1: 1,1,U,0,nan → 2 hot pixels at x=0, x=1
//   row 2: 0,0,0,0,0 → 0 hot
//   total 4 hot pixels.
public class GaMetricTest {

    private static final double EPS = 1e-9;

    private Path segDir() {
        return Paths.get("src/test/resources/retinal/metrics/ga");
    }

    @Test
    public void area_etdrs_and_per_bscan_with_geometry() {
        PixelGeometry geom = new PixelGeometry(0.01, 0.1, 0.1, 3, 100, 5);
        RetinalMetricComputer computer = new RetinalMetricComputer();

        ComputedMetrics m = computer.compute("ga", segDir(), geom, "OD");

        assertEquals("mm²", m.primaryUnit());
        // 4 hot * 0.1 * 0.1 = 0.04 mm²
        assertEquals(new BigDecimal("0.0400"), m.primaryValue());
        assertEquals(0.04, ((Number) m.payload().get("ga_area_mm2")).doubleValue(), EPS);
        assertEquals(4L, ((Number) m.payload().get("hot_pixel_count")).longValue());
        assertEquals("001-RPEL.csv", m.payload().get("rpel_csv"));

        @SuppressWarnings("unchecked")
        Map<String, Object> etdrs = (Map<String, Object>) m.payload().get("etdrs_mm2");
        // All 4 hot pixels fall inside the 1 mm ring (max dist 0.2 mm < 0.5).
        assertEquals(0.04, ((Number) etdrs.get("central_1mm")).doubleValue(), EPS);
        assertEquals(0.04, ((Number) etdrs.get("central_3mm")).doubleValue(), EPS);
        assertEquals(0.04, ((Number) etdrs.get("central_6mm")).doubleValue(), EPS);

        @SuppressWarnings("unchecked")
        Map<String, Object> center = (Map<String, Object>) m.payload().get("etdrs_center");
        assertEquals(1, ((Number) center.get("bscan_z")).intValue());
        assertEquals(2, ((Number) center.get("ascan_x")).intValue());
        assertEquals("volume-center-mvp", center.get("source"));

        double[] perB = (double[]) m.payload().get("per_bscan_mm2");
        assertEquals(3, perB.length);
        assertEquals(0.02, perB[0], EPS);
        assertEquals(0.02, perB[1], EPS);
        assertEquals(0.0, perB[2], EPS);
    }

    @Test
    public void soft_fails_to_pixel_units_when_geometry_missing() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        ComputedMetrics m = computer.compute("ga", segDir(), null, "OS");
        assertEquals("px²", m.primaryUnit());
        // 4 hot * 1 * 1 = 4 px²
        assertEquals(new BigDecimal("4.0000"), m.primaryValue());
        assertEquals(4.0, ((Number) m.payload().get("ga_area_px2")).doubleValue(), EPS);
        assertEquals("missing", m.payload().get("geometry"));
    }
}
