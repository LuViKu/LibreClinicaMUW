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
import java.util.List;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceCsvReader;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceGrid;

/**
 * DR-022 — ONL (outer nuclear layer) thickness in µm.
 *
 * <p>Upper boundary: {@code 001-OPL-HFL.csv}; lower boundary:
 * {@code 002-BMEIS.csv}. Per A-scan thickness pixels = lower_y − upper_y,
 * NaN-propagating; nan-mean over all (B-scan × A-scan) entries × axialMm
 * × 1000 → µm.
 *
 * <p>Note: BMEIS is the <i>lower</i> bound for ONL but the <i>upper</i>
 * bound for PR — hence {@link OnlMetric} and {@link PrMetric} are
 * separate (different conventions, same shape).
 */
final class OnlMetric {

    // The cluster emits long human-readable filenames like
    // "001-OPL-Henle's fiber layer (OPL-HFL).csv" — we resolve by substring
    // so both the canonical short form and the long form match.
    private static final String UPPER_TOKEN = "OPL-HFL";
    private static final String LOWER_TOKEN = "BMEIS";

    private OnlMetric() { }

    static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality) {
        return ThicknessMetric.compute(segDir, geom, laterality,
                UPPER_TOKEN, LOWER_TOKEN, "ONL");
    }

    /** Shared helper since ONL and PR differ only in surface filenames. */
    static final class ThicknessMetric {

        private ThicknessMetric() { }

        static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality,
                                       String upperToken, String lowerToken, String layerName) {
            Path upper = findSurface(segDir, upperToken);
            Path lower = findSurface(segDir, lowerToken);
            if (upper == null) {
                throw new MetricComputationException(
                        "no surface CSV matching '" + upperToken + "' in " + segDir);
            }
            if (lower == null) {
                throw new MetricComputationException(
                        "no surface CSV matching '" + lowerToken + "' in " + segDir);
            }
            String upperCsv = upper.getFileName().toString();
            String lowerCsv = lower.getFileName().toString();

            SurfaceGrid up;
            SurfaceGrid lo;
            try {
                up = SurfaceCsvReader.read(upper);
                lo = SurfaceCsvReader.read(lower);
            } catch (IOException e) {
                throw new MetricComputationException(
                        "failed to read surface CSVs in " + segDir, e);
            }

            if (up.nBscans() != lo.nBscans() || up.nAscans() != lo.nAscans()) {
                throw new MetricComputationException(
                        layerName + " surface dims disagree: upper=" + up.nBscans()
                                + "x" + up.nAscans() + " lower=" + lo.nBscans()
                                + "x" + lo.nAscans());
            }

            int nBscans = up.nBscans();
            int nAscans = up.nAscans();
            int totalAscans = nBscans * nAscans;

            double sum = 0.0;
            long validCount = 0L;
            for (int b = 0; b < nBscans; b++) {
                double[] uRow = up.yPerBscan()[b];
                double[] lRow = lo.yPerBscan()[b];
                for (int a = 0; a < nAscans; a++) {
                    double u = uRow[a];
                    double l = lRow[a];
                    if (Double.isNaN(u) || Double.isNaN(l)) {
                        continue;
                    }
                    sum += (l - u);
                    validCount++;
                }
            }

            boolean haveGeom = geom != null;
            double axialMm = haveGeom ? geom.axialMm() : 1.0;

            double meanPx = validCount > 0 ? (sum / validCount) : Double.NaN;
            double thicknessValue;
            String thicknessKey;
            String unit;
            if (haveGeom) {
                thicknessValue = Double.isNaN(meanPx) ? 0.0 : meanPx * axialMm * 1000.0;
                thicknessKey = "thickness_mean_um";
                unit = "µm";
            } else {
                thicknessValue = Double.isNaN(meanPx) ? 0.0 : meanPx;
                thicknessKey = "thickness_mean_px";
                unit = "px";
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(thicknessKey, thicknessValue);
            payload.put("valid_ascans", validCount);
            payload.put("total_ascans", totalAscans);
            payload.put("surface_csvs", List.of(upperCsv, lowerCsv));
            payload.put("axial_mm_per_px", axialMm);
            payload.put("layer", layerName);
            if (laterality != null) {
                payload.put("laterality", laterality);
            }
            if (!haveGeom) {
                payload.put("geometry", "missing");
            }

            BigDecimal primary = BigDecimal.valueOf(thicknessValue).setScale(4, RoundingMode.HALF_UP);
            return new ComputedMetrics(primary, unit, payload);
        }

        /**
         * Return the first {@code .csv} under {@code segDir} whose basename
         * contains {@code token} (case-sensitive — runner filenames are
         * fixed), or {@code null} when none match. Subdirectories are not
         * walked: each task's outputs are flat.
         */
        static Path findSurface(Path segDir, String token) {
            try (java.util.stream.Stream<Path> entries = Files.list(segDir)) {
                return entries
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".csv") && n.contains(token);
                        })
                        .sorted()
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                throw new MetricComputationException(
                        "failed to list " + segDir + " while seeking '" + token + "'", e);
            }
        }
    }
}
