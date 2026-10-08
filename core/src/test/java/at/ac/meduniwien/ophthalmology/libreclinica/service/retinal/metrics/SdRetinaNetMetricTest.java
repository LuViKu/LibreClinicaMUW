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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

/**
 * The fixture under {@code retinal/metrics/sdretinanet} was written by the real
 * layerlib/lesionlib writers, and {@code expected.json} holds what the SWITCHER
 * study's Python code ({@code namd_switcher}) computes for it; see
 * {@code generate_fixture.py} there. These tests hold the Java port to those
 * numbers.
 */
public class SdRetinaNetMetricTest {

    // generate_fixture.py: 33 B-scans x 64 A-scans x 80 rows
    private static final PixelGeometry GEOM = new PixelGeometry(0.006, 0.095, 0.19, 33, 80, 64);
    private static final double REL = 1e-9;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private static Path dir() {
        return Paths.get("src/test/resources/retinal/metrics/sdretinanet");
    }

    private static JsonNode expected() throws Exception {
        return JSON.readTree(dir().resolve("expected.json").toFile());
    }

    private static Map<String, Object> payload() {
        return new RetinalMetricComputer().compute("sdretinanet", dir(), GEOM, "OD").payload();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static void assertClose(String what, double want, Object got) {
        if (Double.isNaN(want)) {
            assertNull(what + " should be missing", got);
            return;
        }
        assertNotNull(what, got);
        double g = ((Number) got).doubleValue();
        assertEquals(what, want, g, Math.max(1e-12, Math.abs(want) * REL));
    }

    @Test
    public void fovea_matches_namd_switcher() throws Exception {
        JsonNode want = expected().get("fovea");
        Map<String, Object> fovea = map(payload().get("fovea"));
        assertClose("x_mm", want.get("fovea_x_mm").asDouble(), fovea.get("x_mm"));
        assertClose("y_mm", want.get("fovea_y_mm").asDouble(), fovea.get("y_mm"));
        assertClose("offset_mm", want.get("fovea_offset_mm").asDouble(), fovea.get("offset_mm"));
        assertClose("inner_retina_um", want.get("fovea_inner_retina_um").asDouble(), fovea.get("inner_retina_um"));
        assertClose("pit_depth_um", want.get("fovea_pit_depth_um").asDouble(), fovea.get("pit_depth_um"));
        assertEquals(want.get("fovea_needs_review").asBoolean(), fovea.get("needs_review"));
        assertEquals(want.get("fovea_at_search_edge").asBoolean(), fovea.get("at_search_edge"));

        Map<String, Object> grid = map(payload().get("grid_center"));
        assertEquals(expected().get("grid_center").get("source").asText(), grid.get("source"));
    }

    @Test
    public void every_etdrs_value_matches_namd_switcher() throws Exception {
        Map<String, Object> etdrs = map(payload().get("etdrs"));
        int checked = 0;
        for (JsonNode row : expected().get("regions")) {
            String region = row.get("region").asText();
            String biomarker = row.get("biomarker").asText();
            String metric = row.get("metric").asText();
            double value = row.get("value").asDouble();
            Map<String, Object> reg = map(etdrs.get(region));
            assertClose(region + " coverage", row.get("coverage").asDouble(), reg.get("coverage"));
            assertEquals(region + " n_ascans", row.get("n_ascans").asInt(), reg.get("n_ascans"));
            Map<String, Object> layers = map(reg.get("layers"));
            Map<String, Object> lesions = map(reg.get("lesions"));
            Map<String, Object> group = layers.containsKey(biomarker)
                    ? map(layers.get(biomarker)) : map(lesions.get(biomarker));
            assertNotNull(region + " " + biomarker, group);
            assertClose(region + " " + biomarker + " " + metric, value, group.get(metric));
            checked++;
        }
        // 5 regions x (18 layers x 2 + 7 lesions x 3 + HRF foci)
        assertEquals(5 * (18 * 2 + 7 * 3 + 1), checked);
    }

    @Test
    public void whole_scan_lesions_and_primary_value() throws Exception {
        JsonNode voxels = expected().get("total_voxels");
        Map<String, Object> lesions = map(payload().get("lesions"));
        double voxelMm3 = 0.006 * 0.095 * 0.19;
        assertClose("IRF", voxels.get("Cyst").asLong() * voxelMm3, map(lesions.get("IRF")).get("volume_mm3"));
        assertClose("SDD", voxels.get("Pseudodrusen").asLong() * voxelMm3, map(lesions.get("SDD")).get("volume_mm3"));
        assertClose("HRF", voxels.get("HRF").asLong() * voxelMm3, map(lesions.get("HRF")).get("volume_mm3"));
        assertEquals(4, map(lesions.get("HRF")).get("foci_n"));

        ComputedMetrics m = new RetinalMetricComputer().compute("sdretinanet", dir(), GEOM, "OD");
        long fluid = voxels.get("Cyst").asLong() + voxels.get("SRF").asLong() + voxels.get("PED").asLong();
        assertEquals("mm³", m.primaryUnit());
        assertEquals(BigDecimal.valueOf(fluid * voxelMm3).setScale(4, java.math.RoundingMode.HALF_UP),
                m.primaryValue());
        assertNotNull(m.payload().get("crt_um"));
        // nested only: top-level fluid keys would overwrite the fluid task's CRF items
        assertFalse(m.payload().containsKey("irf_mm3"));
        assertFalse(m.payload().containsKey("total_fluid_volume_mm3"));
    }

    @Test
    public void per_bscan_cross_sections() {
        Map<String, Object> perBscan = map(payload().get("per_bscan_mm2"));
        double[] irf = (double[]) perBscan.get("irf");
        assertEquals(33, irf.length);
        // Cyst: B-scans 16..19, 3 rows x 7 A-scans, minus nothing (HRF is an overlay)
        assertEquals(21 * 0.006 * 0.095, irf[17], 1e-12);
        assertEquals(0.0, irf[15], 0.0);
    }

    @Test
    public void without_geometry_reports_pixels_and_skips_the_grid() {
        ComputedMetrics m = new RetinalMetricComputer().compute("sdretinanet", dir(), null, "OD");
        assertEquals("px³", m.primaryUnit());
        assertEquals("missing", m.payload().get("geometry"));
        assertFalse(m.payload().containsKey("etdrs"));
        assertTrue(m.payload().containsKey("lesions"));
    }

    @Test(expected = MetricComputationException.class)
    public void geometry_that_disagrees_with_the_segmentation_fails() {
        new RetinalMetricComputer().compute("sdretinanet", dir(),
                new PixelGeometry(0.006, 0.095, 0.19, 49, 80, 64), "OD");
    }

    @Test
    public void flat_inner_retina_needs_review_and_falls_back() {
        double[] x = new double[40];
        double[] y = new double[20];
        for (int a = 0; a < x.length; a++) x[a] = (a + 0.5) * 0.1;
        for (int b = 0; b < y.length; b++) y[b] = b * 0.2;
        double[][] t = new double[y.length][x.length];
        for (double[] row : t) java.util.Arrays.fill(row, 100.0);
        Map<String, Object> f = SdRetinaNetMetric.detectFovea(t, x, y);
        assertEquals(Boolean.TRUE, f.get("needs_review"));
        assertEquals(0.0, ((Number) f.get("pit_depth_um")).doubleValue(), 1e-9);
    }

    @Test
    public void reflect_and_arange_follow_numpy() {
        assertEquals(0, SdRetinaNetMetric.reflect(-1, 4));
        assertEquals(3, SdRetinaNetMetric.reflect(4, 4));
        assertEquals(2, SdRetinaNetMetric.reflect(-3, 4));
        assertEquals(1, SdRetinaNetMetric.reflect(9, 4));
        assertEquals(3, SdRetinaNetMetric.arange(0, 0.05, 0.02).length);
        assertEquals(0, SdRetinaNetMetric.arange(1, 1, 0.02).length);
    }
}
