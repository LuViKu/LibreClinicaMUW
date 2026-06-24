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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;

/**
 * Unit tests for {@link CrtComputer}.
 *
 * <p>The math: thickness_px = BM_y − ILM_y, averaged over the central
 * 1 mm disk, × axialMm × 1000 → µm.
 *
 * <p>For the synthetic geometry used in {@link #happyPath_uniformSurfaces}
 * (dimX=100, dimY=512, dimZ=49, lateral=0.02 mm, slice=0.02 mm) every
 * pixel within √((50·0.02)² + (z·0.02)²) ≤ 0.5 mm sits in the disk —
 * a full circle of radius 25 pixels in both axes, ≈ π·25² ≈ 1963 pixels.
 * Verified empirically in the assertion.
 */
public class CrtComputerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final double AXIAL_MM = 0.004;     // 4 µm per axial pixel — typical Spectralis
    private static final double LATERAL_MM = 0.02;    // 20 µm per A-scan
    private static final double SLICE_MM = 0.02;      // 20 µm per B-scan — DENSE stack for tight test geometry

    @Test
    public void happyPath_uniformSurfaces() throws IOException {
        // 49 B-scans x 100 A-scans, uniform ILM=50, BM=130 → thickness=80 px.
        // Expected CRT = 80 × 0.004 × 1000 = 320.0 µm.
        Path ilm = writeUniformSurface("ilm.csv", 49, 100, 50);
        Path bm = writeUniformSurface("bm.csv", 49, 100, 130);
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, 49, 512, 100);

        CrtComputer.Result r = new CrtComputer().computeCrtMicrons(ilm, bm, geom);

        assertEquals("uniform 80-px thickness × 4 µm/px = 320 µm", 320.0, r.crtMicrons(), 1e-6);
        assertTrue("disk should hold > 1500 pixels at 0.02 mm/A-scan & 0.02 mm/B-scan",
                r.pixelsInDisk() > 1500);
    }

    @Test
    public void varyingSurfaces_meanThicknessLandsCorrectly() throws IOException {
        // 49 × 100; ILM stays at 50, BM is 130 at center, 110 at edges.
        // Inside the 1mm disk (radius 25 px each axis) BM tapers from
        // 130 at center down to 110 at the disk rim. The mean thickness
        // over a disk is the area-weighted average; with a linear taper
        // from 80 (center thickness) down to 60 (rim) on a disk, the
        // mean is exactly 80 − 20/3 × … — we just sanity-check the
        // value lands strictly between 60 px (320 − 20·0.004·1000 = 240)
        // and 80 px (320) edges + that all pixels in disk are sampled.
        int nB = 49, nA = 100;
        Path ilm = writeUniformSurface("ilm.csv", nB, nA, 50);
        Path bm = writeTaperedSurface("bm.csv", nB, nA, 130, 110);
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, nB, 512, nA);

        CrtComputer.Result r = new CrtComputer().computeCrtMicrons(ilm, bm, geom);

        // Center thickness = 80 px → 320 µm; rim thickness = 60 px → 240 µm.
        // Disk mean must land strictly inside that band.
        assertTrue("CRT " + r.crtMicrons() + " should be > 240 µm (rim) and < 320 µm (center)",
                r.crtMicrons() > 240.0 && r.crtMicrons() < 320.0);
    }

    @Test
    public void shapeMismatch_ilmVsBm_throws() throws IOException {
        Path ilm = writeUniformSurface("ilm.csv", 49, 100, 50);
        Path bm = writeUniformSurface("bm.csv", 49, 99, 130);
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, 49, 512, 100);
        try {
            new CrtComputer().computeCrtMicrons(ilm, bm, geom);
            fail("Expected width-mismatch MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("message mentions width mismatch: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("width"));
        }
    }

    @Test
    public void shapeMismatch_csvVsGeometry_throws() throws IOException {
        Path ilm = writeUniformSurface("ilm.csv", 49, 100, 50);
        Path bm = writeUniformSurface("bm.csv", 49, 100, 130);
        // Geometry says 200 A-scans but CSVs deliver 100.
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, 49, 512, 200);
        try {
            new CrtComputer().computeCrtMicrons(ilm, bm, geom);
            fail("Expected geometry-mismatch MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("message mentions shape vs geometry: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("does not match geometry"));
        }
    }

    @Test
    public void bmBelowIlm_throws_protectsAgainstFileSwap() throws IOException {
        // BM rows below ILM (smaller Y in the input but spec says BM should
        // always be DEEPER → larger Y). Catches a swap of the two args.
        Path ilm = writeUniformSurface("ilm.csv", 49, 100, 130);
        Path bm = writeUniformSurface("bm.csv", 49, 100, 50);
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, 49, 512, 100);
        try {
            new CrtComputer().computeCrtMicrons(ilm, bm, geom);
            fail("Expected BM-above-ILM swap MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("message mentions swap / BM > ILM: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("swap")
                            || ex.getMessage().toLowerCase().contains("bm_y"));
        }
    }

    @Test
    public void tooFewPixelsInDisk_throws_onSparseGeometry() throws IOException {
        // 5 B-scans × 5 A-scans + extreme slice spacing → almost no
        // pixel lands inside the 0.5mm radius disk.
        Path ilm = writeUniformSurface("ilm.csv", 5, 5, 50);
        Path bm = writeUniformSurface("bm.csv", 5, 5, 130);
        // 5 mm/B-scan + 5 mm/A-scan → the central-1mm disk is smaller than
        // a single pixel; only the center pixel itself qualifies.
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, 5.0, 5.0, 5, 512, 5);
        try {
            new CrtComputer().computeCrtMicrons(ilm, bm, geom);
            fail("Expected too-few-pixels MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("message mentions pixel count: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("pixels"));
        }
    }

    @Test
    public void nullGeometry_throws() throws IOException {
        Path ilm = writeUniformSurface("ilm.csv", 5, 5, 50);
        Path bm = writeUniformSurface("bm.csv", 5, 5, 130);
        try {
            new CrtComputer().computeCrtMicrons(ilm, bm, null);
            fail("Expected null-geometry MetricComputationException");
        } catch (MetricComputationException ex) {
            assertTrue("message mentions geometry: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("geometry"));
        }
    }

    @Test
    public void csvHeaderIsIgnored_onlyRowsContributeShape() throws IOException {
        // Even when the header line LIES about n_ascans, the actual data
        // rows are authoritative. We still cross-check against the
        // PixelGeometry, so this is a non-failing case — the math just
        // works off the row widths.
        Path ilm = writeUniformSurface("ilm.csv", 49, 100, 50, "999, 999, 999");
        Path bm = writeUniformSurface("bm.csv", 49, 100, 130, "0, 0, 0");
        PixelGeometry geom = new PixelGeometry(AXIAL_MM, LATERAL_MM, SLICE_MM, 49, 512, 100);
        CrtComputer.Result r = new CrtComputer().computeCrtMicrons(ilm, bm, geom);
        assertEquals(320.0, r.crtMicrons(), 1e-6);
    }

    /* ====================================================================== */
    /* Fixture writers                                                        */
    /* ====================================================================== */

    private Path writeUniformSurface(String name, int nBscans, int nAscans, int y) throws IOException {
        return writeUniformSurface(name, nBscans, nAscans, y, nAscans + ", " + nBscans + ", 512");
    }

    private Path writeUniformSurface(String name, int nBscans, int nAscans, int y, String header)
            throws IOException {
        Path p = tmp.newFile(name).toPath();
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < nAscans; i++) {
            if (i > 0) row.append(',');
            row.append(y);
        }
        String rowStr = row.toString();
        StringBuilder out = new StringBuilder();
        out.append(header).append('\n');
        for (int z = 0; z < nBscans; z++) {
            out.append(rowStr).append('\n');
        }
        Files.write(p, out.toString().getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** Linear radial taper from {@code yCenter} at scan center down to
     *  {@code yRim} at the 25-px (= 0.5 mm) disk rim, clamped above. */
    private Path writeTaperedSurface(String name, int nBscans, int nAscans, int yCenter, int yRim)
            throws IOException {
        Path p = tmp.newFile(name).toPath();
        double cx = nAscans / 2.0;
        double cz = nBscans / 2.0;
        double rimPx = 0.5 / 0.02; // 25 px at 0.02 mm/px
        StringBuilder out = new StringBuilder();
        out.append(nAscans).append(", ").append(nBscans).append(", 512\n");
        for (int z = 0; z < nBscans; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < nAscans; x++) {
                double dx = x - cx, dz = z - cz;
                double rad = Math.sqrt(dx * dx + dz * dz);
                int y;
                if (rad >= rimPx) {
                    y = yRim;
                } else {
                    double t = rad / rimPx;
                    y = (int) Math.round(yCenter * (1 - t) + yRim * t);
                }
                if (x > 0) row.append(',');
                row.append(y);
            }
            out.append(row).append('\n');
        }
        Files.write(p, out.toString().getBytes(StandardCharsets.UTF_8));
        return p;
    }
}
