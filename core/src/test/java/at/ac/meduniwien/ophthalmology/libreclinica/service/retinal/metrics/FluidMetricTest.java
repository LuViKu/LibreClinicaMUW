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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

// Fixture generated with /tmp/gen_fluid_fixture.py — 4x4x4 uint8 npz with:
//   seg[2,2,2]=1 (IRF at fovea)
//   seg[2,2,3]=1 (IRF +1 lateral)
//   seg[0,0,0]=2 (SRF corner)
//   seg[3,3,3]=3 (PED opposite corner)
// Geometry: axial=lateral=slice=0.1 mm/px → voxelVol=0.001 mm³.
public class FluidMetricTest {

    private static final double EPS = 1e-9;

    private Path segDir() {
        return Paths.get("src/test/resources/retinal/metrics/fluid");
    }

    @Test
    public void computes_biomarker_volumes_etdrs_and_per_bscan() {
        PixelGeometry geom = new PixelGeometry(0.1, 0.1, 0.1, 4, 4, 4);
        RetinalMetricComputer computer = new RetinalMetricComputer();

        ComputedMetrics m = computer.compute("fluid", segDir(), geom, "OD");

        assertEquals("mm³", m.primaryUnit());
        assertEquals(new BigDecimal("0.0040"), m.primaryValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> bio = (Map<String, Object>) m.payload().get("biomarkers");
        assertEquals(0.002, ((Number) bio.get("irf_mm3")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) bio.get("srf_mm3")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) bio.get("ped_mm3")).doubleValue(), EPS);
        assertEquals(0.004, ((Number) bio.get("total_mm3")).doubleValue(), EPS);

        @SuppressWarnings("unchecked")
        Map<String, Object> etdrs = (Map<String, Object>) m.payload().get("etdrs_mm3");
        @SuppressWarnings("unchecked")
        Map<String, Object> ring1 = (Map<String, Object>) etdrs.get("central_1mm");
        @SuppressWarnings("unchecked")
        Map<String, Object> ring3 = (Map<String, Object>) etdrs.get("central_3mm");
        @SuppressWarnings("unchecked")
        Map<String, Object> ring6 = (Map<String, Object>) etdrs.get("central_6mm");

        // Every voxel sits within 0.5 mm of the fovea in this tiny test volume
        // (max dist sqrt(0.04+0.04)=0.283 < 0.5), so all three rings are full.
        assertEquals(0.002, ((Number) ring1.get("irf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring1.get("srf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring1.get("ped")).doubleValue(), EPS);
        assertEquals(0.004, ((Number) ring1.get("total")).doubleValue(), EPS);

        assertEquals(0.002, ((Number) ring3.get("irf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring3.get("srf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring3.get("ped")).doubleValue(), EPS);
        assertEquals(0.004, ((Number) ring3.get("total")).doubleValue(), EPS);

        assertEquals(0.002, ((Number) ring6.get("irf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring6.get("srf")).doubleValue(), EPS);
        assertEquals(0.001, ((Number) ring6.get("ped")).doubleValue(), EPS);
        assertEquals(0.004, ((Number) ring6.get("total")).doubleValue(), EPS);

        @SuppressWarnings("unchecked")
        Map<String, Object> center = (Map<String, Object>) m.payload().get("etdrs_center");
        assertEquals(2, ((Number) center.get("bscan_z")).intValue());
        assertEquals(2, ((Number) center.get("ascan_x")).intValue());
        assertEquals("volume-center-mvp", center.get("source"));

        assertEquals(0.001, ((Number) m.payload().get("voxel_volume_mm3")).doubleValue(), EPS);

        @SuppressWarnings("unchecked")
        Map<String, Object> perB = (Map<String, Object>) m.payload().get("per_bscan_mm2");
        double[] irf = (double[]) perB.get("irf");
        double[] srf = (double[]) perB.get("srf");
        double[] ped = (double[]) perB.get("ped");
        // z=2 holds two IRF voxels → 2 * 0.01 = 0.02 mm² (each voxel face is
        // axial * lateral = 0.01 mm²).
        assertEquals(0.0, irf[0], EPS);
        assertEquals(0.0, irf[1], EPS);
        assertEquals(0.02, irf[2], EPS);
        assertEquals(0.0, irf[3], EPS);
        assertEquals(0.01, srf[0], EPS);
        assertEquals(0.01, ped[3], EPS);

        assertEquals("fluidseg.npz", m.payload().get("segmentation_file"));
        assertEquals("OD", m.payload().get("laterality"));
    }

    @Test
    public void soft_fails_to_pixel_units_when_geometry_missing() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        ComputedMetrics m = computer.compute("fluid", segDir(), null, "OS");

        assertEquals("px³", m.primaryUnit());
        // Voxel volume = 1.0 px³ → total = 4 px³.
        assertEquals(new BigDecimal("4.0000"), m.primaryValue());
        assertEquals("missing", m.payload().get("geometry"));
        assertNotNull(m.payload().get("biomarkers"));
    }

    @Test
    public void rounding_uses_half_up_to_four_places() {
        // Sanity: BigDecimal scaling is HALF_UP at scale 4. Construct a known
        // value (irrelevant which task) and confirm the contract.
        BigDecimal v = BigDecimal.valueOf(0.00125).setScale(4, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("0.0013"), v);
        assertTrue(v.scale() == 4);
    }
}
