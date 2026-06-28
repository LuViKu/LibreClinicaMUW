/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceCsvReader;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceGrid;

/**
 * DR-022 — geographic-atrophy (GA) area in mm² derived from the binarised
 * {@code 001-RPEL.csv} surface (hot pixel = value &gt; 0.5, NaN ignored).
 *
 * <p>Area mm² = hotCount × lateralMm × sliceMm. ETDRS rings + a
 * per-B-scan area trace follow the same fovea-center MVP convention as
 * {@link FluidMetric}.
 */
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings("null")
final class GaMetric {

    private static final String RPEL_CSV = "001-RPEL.csv";
    private static final double HOT_THRESHOLD = 0.5;

    /** Diameter-named clinical ETDRS rings → radii in mm. */
    private static final double RADIUS_1MM = 0.5;
    private static final double RADIUS_3MM = 1.5;
    private static final double RADIUS_6MM = 3.0;

    private GaMetric() { }

    static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality) {
        Path rpel = segDir.resolve(RPEL_CSV);
        if (!Files.isRegularFile(rpel)) {
            throw new MetricComputationException("missing artifact: " + rpel);
        }
        SurfaceGrid grid;
        try {
            grid = SurfaceCsvReader.read(rpel);
        } catch (IOException e) {
            throw new MetricComputationException("failed to read " + rpel, e);
        }

        int nBscans = grid.nBscans();
        int nAscans = grid.nAscans();

        long hotCount = 0L;
        long ring1 = 0L;
        long ring3 = 0L;
        long ring6 = 0L;
        long[] hotPerBscan = new long[nBscans];

        // Volume-center MVP: real fovea localiser comes later.
        int foveaZ = nBscans / 2;
        int foveaX = nAscans / 2;

        boolean haveGeom = geom != null;
        double lateralMm = haveGeom ? geom.lateralMm() : 1.0;
        double sliceMm   = haveGeom ? geom.sliceMm()   : 1.0;

        for (int z = 0; z < nBscans; z++) {
            double dzMm = (z - foveaZ) * sliceMm;
            double[] row = grid.yPerBscan()[z];
            for (int x = 0; x < nAscans; x++) {
                double v = row[x];
                if (Double.isNaN(v) || v <= HOT_THRESHOLD) {
                    continue;
                }
                hotCount++;
                hotPerBscan[z]++;
                double dxMm = (x - foveaX) * lateralMm;
                double distMm = Math.sqrt(dxMm * dxMm + dzMm * dzMm);
                if (distMm <= RADIUS_6MM) {
                    ring6++;
                    if (distMm <= RADIUS_3MM) {
                        ring3++;
                        if (distMm <= RADIUS_1MM) {
                            ring1++;
                        }
                    }
                }
            }
        }

        double pixelAreaMm2 = lateralMm * sliceMm;
        double gaAreaMm2 = hotCount * pixelAreaMm2;

        Map<String, Object> etdrs = new LinkedHashMap<>();
        etdrs.put("central_1mm", ring1 * pixelAreaMm2);
        etdrs.put("central_3mm", ring3 * pixelAreaMm2);
        etdrs.put("central_6mm", ring6 * pixelAreaMm2);

        Map<String, Object> etdrsCenter = new LinkedHashMap<>();
        etdrsCenter.put("bscan_z", foveaZ);
        etdrsCenter.put("ascan_x", foveaX);
        etdrsCenter.put("source", "volume-center-mvp");

        double[] perBscan = new double[nBscans];
        for (int i = 0; i < nBscans; i++) {
            perBscan[i] = hotPerBscan[i] * pixelAreaMm2;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(haveGeom ? "ga_area_mm2" : "ga_area_px2", gaAreaMm2);
        payload.put("hot_pixel_count", hotCount);
        payload.put("rpel_csv", RPEL_CSV);
        payload.put(haveGeom ? "etdrs_mm2" : "etdrs_px2", etdrs);
        payload.put("etdrs_center", etdrsCenter);
        payload.put(haveGeom ? "per_bscan_mm2" : "per_bscan_px2", perBscan);
        if (laterality != null) {
            payload.put("laterality", laterality);
        }
        if (!haveGeom) {
            payload.put("geometry", "missing");
        }

        String unit = haveGeom ? "mm²" : "px²";
        BigDecimal primary = BigDecimal.valueOf(gaAreaMm2).setScale(4, RoundingMode.HALF_UP);
        return new ComputedMetrics(primary, unit, payload);
    }
}
