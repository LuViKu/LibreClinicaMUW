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
import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

// Mirror of ONL fixture but using BMEIS as upper and OB-OPR as lower.
public class PrMetricTest {

    private static final double EPS = 1e-9;

    private Path segDir() {
        return Paths.get("src/test/resources/retinal/metrics/pr");
    }

    @Test
    public void mean_thickness_um_with_geometry() {
        PixelGeometry geom = new PixelGeometry(0.001, 0.01, 0.05, 3, 100, 5);
        RetinalMetricComputer computer = new RetinalMetricComputer();

        ComputedMetrics m = computer.compute("pr", segDir(), geom, "OS");

        assertEquals("µm", m.primaryUnit());
        assertEquals(new BigDecimal("17.5000"), m.primaryValue());
        assertEquals(17.5, ((Number) m.payload().get("thickness_mean_um")).doubleValue(), EPS);
        assertEquals(10L, ((Number) m.payload().get("valid_ascans")).longValue());
        assertEquals(15, ((Number) m.payload().get("total_ascans")).intValue());
        @SuppressWarnings("unchecked")
        List<String> csvs = (List<String>) m.payload().get("surface_csvs");
        // BMEIS is the UPPER bound for PR; OB-OPR is the lower bound.
        assertEquals(List.of("001-BMEIS.csv", "002-OB-OPR.csv"), csvs);
        assertEquals("PR", m.payload().get("layer"));
    }

    @Test
    public void soft_fails_to_pixel_units_when_geometry_missing() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        ComputedMetrics m = computer.compute("pr", segDir(), null, "OD");
        assertEquals("px", m.primaryUnit());
        assertEquals(new BigDecimal("17.5000"), m.primaryValue());
        assertEquals("missing", m.payload().get("geometry"));
    }
}
