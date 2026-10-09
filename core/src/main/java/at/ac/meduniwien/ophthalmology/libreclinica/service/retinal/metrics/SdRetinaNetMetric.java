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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SdRetinaNetReader;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SdRetinaNetVolume;

/**
 * 2026-10-07 — sdretinanet task metrics: layer thicknesses and lesion
 * volumes over an ETDRS grid centred on the detected fovea.
 *
 * <p>A port of the SWITCHER study's quantification
 * ({@code namd_switcher.fovea}, {@code .biomarkers}, {@code .etdrs},
 * {@code .pipeline}), so the eCRF and that analysis report the same numbers
 * for the same segmentation. {@code SdRetinaNetMetricTest} checks this class
 * against values the Python code computed on a shared fixture.
 *
 * <ul>
 *   <li><b>Fovea</b>: minimum of the inner-retina (ILM to INL-OPL) thickness
 *       map, resampled to a 0.02 mm grid and Gaussian-smoothed (σ 0.1 mm),
 *       within 1.5 mm of the scan centre. A pit shallower than 50 µm or a
 *       minimum at the search edge needs review, and the grid then falls
 *       back to the scan centre.</li>
 *   <li><b>Regions</b>: the central 1, 3 and 6 mm discs and the 1-3 and
 *       3-6 mm rings. The quadrant sectors are left out until the B-scan
 *       order and A-scan direction of the app's own .e2e conversion are
 *       verified; discs and rings do not depend on either.</li>
 *   <li><b>Layers</b>: mean thickness (µm) over the region's valid A-scans,
 *       and volume = mean thickness × nominal region area.</li>
 *   <li><b>Lesions</b>: voxel volume, en-face area and tallest column within
 *       the part of the region inside the scan, so read them together with
 *       {@code coverage}. HRF also gets {@code foci_n}, 2D connected
 *       components per B-scan assigned by centroid.</li>
 * </ul>
 *
 * <p>Each A-scan stands for {@code lateral × B-scan spacing} mm² of fundus.
 * Lesion results sit under nested keys only: the CRF populator reads
 * top-level {@code irf_mm3}-style keys from every done job, and this task
 * must not overwrite the fluid task's items.
 */
final class SdRetinaNetMetric {

    static final String ARCHIVE = SdRetinaNetReader.ARCHIVE;

    static final List<String> REGIONS = List.of("C1", "C3", "C6", "R1_3", "R3_6");

    /** Layer name → (upper boundary, lower boundary), as namd_switcher.biomarkers.LAYERS. */
    static final Map<String, String[]> LAYERS = new LinkedHashMap<>();
    static {
        LAYERS.put("RNFL", new String[]{"ILM", "RNFL-GCL"});
        LAYERS.put("GCL", new String[]{"RNFL-GCL", "GCL-IPL"});
        LAYERS.put("IPL", new String[]{"GCL-IPL", "IPL-INL"});
        LAYERS.put("INL", new String[]{"IPL-INL", "INL-OPL"});
        LAYERS.put("OPL", new String[]{"INL-OPL", "OPL-HFL"});
        LAYERS.put("ONL_HFL", new String[]{"OPL-HFL", "OB_ELM"});
        LAYERS.put("ELM_EZ", new String[]{"OB_ELM", "BMEIS"});
        LAYERS.put("EZ_RPE", new String[]{"BMEIS", "IB_RPE"});
        LAYERS.put("RPE", new String[]{"IB_RPE", "OB_RPE"});
        LAYERS.put("RPE_BM", new String[]{"OB_RPE", "BM"});
        LAYERS.put("Choroid", new String[]{"BM", "HL-S"});
        LAYERS.put("GCIPL", new String[]{"RNFL-GCL", "IPL-INL"});
        LAYERS.put("GCC", new String[]{"ILM", "IPL-INL"});
        LAYERS.put("InnerRetina", new String[]{"ILM", "INL-OPL"});
        LAYERS.put("OuterRetina", new String[]{"INL-OPL", "BM"});
        LAYERS.put("Photoreceptors", new String[]{"OB_ELM", "IB_RPE"});
        LAYERS.put("NeurosensoryRetina", new String[]{"ILM", "IB_RPE"});
        LAYERS.put("TotalRetina", new String[]{"ILM", "BM"});
    }

    /** Model lesion name → display label; other names pass through. */
    static final Map<String, String> LESION_LABELS = Map.of("Cyst", "IRF", "Pseudodrusen", "SDD");

    /** Lesions summed into the primary value, as the fluid task's total. */
    private static final List<String> FLUID_LABELS = List.of("IRF", "SRF", "PED");

    static final double GRID_STEP_MM = 0.02;
    static final double SEARCH_RADIUS_MM = 1.5;
    static final double SMOOTH_MM = 0.1;
    static final double MIN_PIT_DEPTH_UM = 50;

    private SdRetinaNetMetric() { }

    static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality) {
        SdRetinaNetVolume vol;
        try {
            vol = SdRetinaNetReader.read(segDir);
        } catch (IOException e) {
            throw new MetricComputationException("failed to read SD-RetinaNet output in " + segDir, e);
        }
        if (geom != null && (geom.dimZ() != vol.nBscans() || geom.dimX() != vol.width())) {
            throw new MetricComputationException("segmentation is " + vol.nBscans() + " x " + vol.width()
                    + " (B-scans x A-scans), geometry says " + geom.dimZ() + " x " + geom.dimX());
        }
        return compute(vol, geom, laterality);
    }

    static ComputedMetrics compute(SdRetinaNetVolume vol, PixelGeometry geom, String laterality) {
        boolean haveGeom = geom != null;
        double axialMm = haveGeom ? geom.axialMm() : 1.0;
        double lateralMm = haveGeom ? geom.lateralMm() : 1.0;
        double sliceMm = haveGeom ? geom.sliceMm() : 1.0;
        double ascanAreaMm2 = lateralMm * sliceMm;
        int nB = vol.nBscans();
        int nA = vol.width();

        List<String> lesionNames = new ArrayList<>(vol.mainLesions());
        lesionNames.addAll(vol.overlayLesions());
        int nL = lesionNames.size();

        // per-A-scan lesion heights (px) and per-B-scan cross-section counts
        int[][][] height = new int[nL][nB][nA];
        long[][] perBscan = new long[nL][nB];
        int mainMask = vol.mainMask();
        int nMain = vol.mainLesions().size();
        for (int b = 0; b < nB; b++) {
            for (int r = 0; r < vol.height(); r++) {
                for (int a = 0; a < nA; a++) {
                    int v = vol.packed(b, r, a);
                    if (v == 0) continue;
                    int id = v & mainMask;
                    if (id > 0 && id <= nMain) {
                        height[id - 1][b][a]++;
                        perBscan[id - 1][b]++;
                    }
                    for (int k = 0; k < vol.overlayLesions().size(); k++) {
                        if ((v & SdRetinaNetVolume.overlayBit(k)) != 0) {
                            height[nMain + k][b][a]++;
                            perBscan[nMain + k][b]++;
                        }
                    }
                }
            }
        }
        int hrf = lesionNames.indexOf("HRF");
        List<double[]> foci = hrf < 0 ? List.of() : hrfFoci(vol, hrfTest(vol, hrf));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layer_names", vol.layerNames());
        payload.put("lesion_names", lesionNames);

        Map<String, Object> lesionTotals = new LinkedHashMap<>();
        double fluidMm3 = 0;
        for (int i = 0; i < nL; i++) {
            long voxels = 0;
            long ascans = 0;
            int maxH = 0;
            for (int b = 0; b < nB; b++) {
                for (int a = 0; a < nA; a++) {
                    int h = height[i][b][a];
                    voxels += h;
                    if (h > 0) ascans++;
                    maxH = Math.max(maxH, h);
                }
            }
            String label = label(lesionNames.get(i));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("volume_mm3", voxels * axialMm * ascanAreaMm2);
            m.put("area_mm2", ascans * ascanAreaMm2);
            m.put("max_height_um", maxH * axialMm * 1000);
            if (i == hrf) m.put("foci_n", foci.size());
            lesionTotals.put(label, m);
            if (FLUID_LABELS.contains(label)) fluidMm3 += voxels * axialMm * ascanAreaMm2;
        }
        payload.put("lesions", lesionTotals);

        Map<String, Object> perBscanMm2 = new LinkedHashMap<>();
        for (int i = 0; i < nL; i++) {
            double[] mm2 = new double[nB];
            for (int b = 0; b < nB; b++) mm2[b] = perBscan[i][b] * axialMm * lateralMm;
            perBscanMm2.put(label(lesionNames.get(i)).toLowerCase(), mm2);
        }
        payload.put("per_bscan_mm2", perBscanMm2);

        if (haveGeom) {
            double[] xMm = new double[nA];
            double[] yMm = new double[nB];
            for (int a = 0; a < nA; a++) xMm[a] = (a + 0.5) * lateralMm;
            for (int b = 0; b < nB; b++) yMm[b] = b * sliceMm;

            Map<String, Object> fovea = detectFovea(vol, axialMm, xMm, yMm);
            double cx = mean(xMm);
            double cy = mean(yMm);
            boolean detected = !(Boolean) fovea.get("needs_review");
            double gx = detected ? (Double) fovea.get("x_mm") : cx;
            double gy = detected ? (Double) fovea.get("y_mm") : cy;
            payload.put("fovea", fovea);
            Map<String, Object> grid = new LinkedHashMap<>();
            grid.put("x_mm", gx);
            grid.put("y_mm", gy);
            grid.put("source", detected ? "detected" : "scan_center_fallback");
            grid.put("bscan_z", (int) Math.rint(gy / sliceMm));
            grid.put("ascan_x", (int) Math.rint(gx / lateralMm - 0.5));
            payload.put("grid_center", grid);

            Map<String, Object> etdrs = etdrs(vol, height, lesionNames, foci, axialMm, ascanAreaMm2,
                    xMm, yMm, gx, gy, hrf);
            payload.put("etdrs", etdrs);
            @SuppressWarnings("unchecked")
            Map<String, Object> c1 = (Map<String, Object>) etdrs.get("C1");
            @SuppressWarnings("unchecked")
            Map<String, Object> c1Layers = (Map<String, Object>) c1.get("layers");
            @SuppressWarnings("unchecked")
            Map<String, Object> total = (Map<String, Object>) c1Layers.get("TotalRetina");
            if (total != null && total.get("thickness_um") != null) {
                payload.put("crt_um", total.get("thickness_um"));
            }
        } else {
            payload.put("geometry", "missing");
        }
        payload.put("segmentation_file", ARCHIVE);
        if (laterality != null) payload.put("laterality", laterality);

        String unit = haveGeom ? "mm³" : "px³";
        BigDecimal primary = BigDecimal.valueOf(fluidMm3).setScale(4, RoundingMode.HALF_UP);
        return new ComputedMetrics(primary, unit, payload);
    }

    static String label(String lesionName) {
        return LESION_LABELS.getOrDefault(lesionName, lesionName);
    }

    private static Map<String, Object> etdrs(SdRetinaNetVolume vol, int[][][] height, List<String> lesionNames,
                                             List<double[]> foci, double axialMm, double ascanAreaMm2,
                                             double[] xMm, double[] yMm, double gx, double gy, int hrf) {
        int nB = yMm.length;
        int nA = xMm.length;
        double a1 = Math.PI * 0.25;
        double a3 = Math.PI * 1.5 * 1.5;
        double a6 = Math.PI * 9;
        Map<String, Double> areas = Map.of("C1", a1, "C3", a3, "C6", a6, "R1_3", a3 - a1, "R3_6", a6 - a3);

        // region membership per A-scan
        Map<String, boolean[][]> masks = new LinkedHashMap<>();
        for (String region : REGIONS) masks.put(region, new boolean[nB][nA]);
        for (int b = 0; b < nB; b++) {
            for (int a = 0; a < nA; a++) {
                double r = Math.hypot(xMm[a] - gx, yMm[b] - gy);
                boolean c1 = r <= 0.5;
                boolean c3 = r <= 1.5;
                boolean c6 = r <= 3.0;
                masks.get("C1")[b][a] = c1;
                masks.get("C3")[b][a] = c3;
                masks.get("C6")[b][a] = c6;
                masks.get("R1_3")[b][a] = c3 && !c1;
                masks.get("R3_6")[b][a] = c6 && !c3;
            }
        }

        List<String> names = vol.layerNames();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String region : REGIONS) {
            boolean[][] m = masks.get(region);
            int n = 0;
            for (boolean[] row : m) for (boolean in : row) if (in) n++;
            Map<String, Object> reg = new LinkedHashMap<>();
            reg.put("coverage", Math.min(n * ascanAreaMm2 / areas.get(region), 1.0));
            reg.put("n_ascans", n);

            Map<String, Object> layers = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : LAYERS.entrySet()) {
                int upper = names.indexOf(e.getValue()[0]);
                int lower = names.indexOf(e.getValue()[1]);
                if (upper < 0 || lower < 0) continue;
                double sum = 0;
                int count = 0;
                for (int b = 0; b < nB; b++) {
                    for (int a = 0; a < nA; a++) {
                        if (!m[b][a]) continue;
                        double t = vol.boundary(b, lower, a) - vol.boundary(b, upper, a);
                        if (Double.isNaN(t)) continue;
                        sum += t * axialMm * 1000;
                        count++;
                    }
                }
                double mean = count > 0 ? sum / count : Double.NaN;
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("thickness_um", num(mean));
                l.put("volume_mm3", num(mean / 1000 * areas.get(region)));
                layers.put(e.getKey(), l);
            }
            reg.put("layers", layers);

            Map<String, Object> lesions = new LinkedHashMap<>();
            for (int i = 0; i < lesionNames.size(); i++) {
                long voxels = 0;
                long ascans = 0;
                int maxH = 0;
                for (int b = 0; b < nB; b++) {
                    for (int a = 0; a < nA; a++) {
                        if (!m[b][a]) continue;
                        int h = height[i][b][a];
                        voxels += h;
                        if (h > 0) ascans++;
                        maxH = Math.max(maxH, h);
                    }
                }
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("volume_mm3", voxels * axialMm * ascanAreaMm2);
                l.put("area_mm2", ascans * ascanAreaMm2);
                l.put("max_height_um", n > 0 ? maxH * axialMm * 1000 : null);
                if (i == hrf) {
                    int inRegion = 0;
                    for (double[] f : foci) {
                        int fb = (int) f[0];
                        int fa = (int) Math.max(0, Math.min(nA - 1, Math.rint(f[1])));
                        if (m[fb][fa]) inRegion++;
                    }
                    l.put("foci_n", inRegion);
                }
                lesions.put(label(lesionNames.get(i)), l);
            }
            reg.put("lesions", lesions);
            out.put(region, reg);
        }
        return out;
    }

    /** Whether a packed pixel holds lesion {@code index} of main-then-overlay {@code lesionNames}. */
    static IntPredicate hrfTest(SdRetinaNetVolume vol, int index) {
        int nMain = vol.mainLesions().size();
        if (index < nMain) {
            int mask = vol.mainMask();
            return v -> (v & mask) == index + 1;
        }
        int bit = SdRetinaNetVolume.overlayBit(index - nMain);
        return v -> (v & bit) != 0;
    }

    /**
     * HRF foci: 4-connected components per B-scan, as {@code [bscan, centroid
     * column]} (scipy.ndimage.label's default structure).
     */
    static List<double[]> hrfFoci(SdRetinaNetVolume vol, IntPredicate isHrf) {
        int w = vol.width();
        int h = vol.height();
        List<double[]> foci = new ArrayList<>();
        int[] stack = new int[w * h];
        for (int b = 0; b < vol.nBscans(); b++) {
            boolean[] seen = new boolean[w * h];
            for (int start = 0; start < w * h; start++) {
                if (seen[start] || !isHrf.test(vol.packed(b, start / w, start % w))) continue;
                int top = 0;
                stack[top++] = start;
                seen[start] = true;
                long n = 0;
                double colSum = 0;
                while (top > 0) {
                    int p = stack[--top];
                    int r = p / w;
                    int c = p % w;
                    n++;
                    colSum += c;
                    int[][] nb = {{r - 1, c}, {r + 1, c}, {r, c - 1}, {r, c + 1}};
                    for (int[] q : nb) {
                        if (q[0] < 0 || q[0] >= h || q[1] < 0 || q[1] >= w) continue;
                        int qi = q[0] * w + q[1];
                        if (!seen[qi] && isHrf.test(vol.packed(b, q[0], q[1]))) {
                            seen[qi] = true;
                            stack[top++] = qi;
                        }
                    }
                }
                foci.add(new double[]{b, colSum / n});
            }
        }
        return foci;
    }

    /** namd_switcher.fovea.detect_fovea on the volume's ILM and INL-OPL boundaries. */
    static Map<String, Object> detectFovea(SdRetinaNetVolume vol, double axialMm, double[] xMm, double[] yMm) {
        int ilm = vol.layerNames().indexOf("ILM");
        int inlOpl = vol.layerNames().indexOf("INL-OPL");
        if (ilm < 0 || inlOpl < 0) {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("needs_review", true);
            none.put("reason", "ILM or INL-OPL boundary missing");
            return none;
        }
        int nB = yMm.length;
        int nA = xMm.length;
        double[][] t = new double[nB][nA];
        for (int b = 0; b < nB; b++) {
            for (int a = 0; a < nA; a++) {
                t[b][a] = (vol.boundary(b, inlOpl, a) - vol.boundary(b, ilm, a)) * axialMm * 1000;
            }
        }
        return detectFovea(t, xMm, yMm);
    }

    /** Fovea from an inner-retina thickness map {@code t[bscan][ascan]} (µm, NaN = missing). */
    static Map<String, Object> detectFovea(double[][] t, double[] xMm, double[] yMm) {
        int nB = yMm.length;
        int nA = xMm.length;
        double fill = nanMedian(t);
        double[][] tm = new double[nB][];
        for (int b = 0; b < nB; b++) {
            tm[b] = t[b].clone();
            for (int a = 0; a < nA; a++) if (Double.isNaN(tm[b][a])) tm[b][a] = fill;
        }
        double[] ys = arange(yMm[0], yMm[nB - 1], GRID_STEP_MM);
        double[] xs = arange(xMm[0], xMm[nA - 1], GRID_STEP_MM);
        double[][] iso = new double[ys.length][xs.length];
        for (int i = 0; i < ys.length; i++) {
            for (int j = 0; j < xs.length; j++) {
                iso[i][j] = bilinear(tm, yMm, xMm, ys[i], xs[j]);
            }
        }
        iso = gaussian(iso, SMOOTH_MM / GRID_STEP_MM);

        double cx = mean(xMm);
        double cy = mean(yMm);
        int bi = -1;
        int bj = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ys.length; i++) {
            for (int j = 0; j < xs.length; j++) {
                if (Math.hypot(xs[j] - cx, ys[i] - cy) <= SEARCH_RADIUS_MM && iso[i][j] < best) {
                    best = iso[i][j];
                    bi = i;
                    bj = j;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (bi < 0) {
            out.put("needs_review", true);
            out.put("reason", "no grid point within the search radius");
            return out;
        }
        double fx = xs[bj];
        double fy = ys[bi];
        List<Double> rim = new ArrayList<>();
        for (int i = 0; i < ys.length; i++) {
            for (int j = 0; j < xs.length; j++) {
                double rf = Math.hypot(xs[j] - fx, ys[i] - fy);
                if (rf >= 0.75 && rf <= 1.25) rim.add(iso[i][j]);
            }
        }
        double pitDepth = rim.isEmpty() ? Double.NaN : median(rim) - best;
        boolean atEdge = Math.hypot(fx - cx, fy - cy) > SEARCH_RADIUS_MM - 2 * GRID_STEP_MM;
        out.put("x_mm", fx);
        out.put("y_mm", fy);
        out.put("offset_mm", Math.hypot(fx - cx, fy - cy));
        out.put("inner_retina_um", best);
        out.put("pit_depth_um", num(pitDepth));
        out.put("at_search_edge", atEdge);
        out.put("needs_review", atEdge || !(pitDepth >= MIN_PIT_DEPTH_UM));
        return out;
    }

    /** numpy.arange(start, stop, step). */
    static double[] arange(double start, double stop, double step) {
        int n = (int) Math.max(0, Math.ceil((stop - start) / step));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = start + i * step;
        return out;
    }

    /**
     * Linear interpolation on the (y, x) grid, as scipy's RegularGridInterpolator.
     * Points a rounding error past the last grid line are clamped onto it.
     */
    static double bilinear(double[][] v, double[] gy, double[] gx, double y, double x) {
        int i = segment(gy, y);
        int j = segment(gx, x);
        double wy = gy.length > 1 ? clamp01((y - gy[i]) / (gy[i + 1] - gy[i])) : 0;
        double wx = gx.length > 1 ? clamp01((x - gx[j]) / (gx[j + 1] - gx[j])) : 0;
        int i1 = Math.min(i + 1, gy.length - 1);
        int j1 = Math.min(j + 1, gx.length - 1);
        return (1 - wy) * ((1 - wx) * v[i][j] + wx * v[i][j1])
                + wy * ((1 - wx) * v[i1][j] + wx * v[i1][j1]);
    }

    private static int segment(double[] g, double p) {
        int idx = Arrays.binarySearch(g, p);
        int i = idx >= 0 ? idx : -idx - 2;
        return Math.max(0, Math.min(i, Math.max(0, g.length - 2)));
    }

    private static double clamp01(double w) {
        return Math.max(0, Math.min(1, w));
    }

    /** scipy.ndimage.gaussian_filter (truncate 4, mode 'reflect'), axis 0 then axis 1. */
    static double[][] gaussian(double[][] in, double sigma) {
        int radius = (int) (4.0 * sigma + 0.5);
        double[] k = new double[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            k[i + radius] = Math.exp(-0.5 / (sigma * sigma) * i * i);
            sum += k[i + radius];
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        int rows = in.length;
        int cols = rows == 0 ? 0 : in[0].length;
        double[][] tmp = new double[rows][cols];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                double acc = 0;
                for (int d = -radius; d <= radius; d++) acc += k[d + radius] * in[reflect(r + d, rows)][c];
                tmp[r][c] = acc;
            }
        }
        double[][] out = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double acc = 0;
                for (int d = -radius; d <= radius; d++) acc += k[d + radius] * tmp[r][reflect(c + d, cols)];
                out[r][c] = acc;
            }
        }
        return out;
    }

    /** 'reflect' boundary: d c b a | a b c d | d c b a. */
    static int reflect(int i, int n) {
        int period = 2 * n;
        int m = Math.floorMod(i, period);
        return m < n ? m : period - 1 - m;
    }

    /** JSONB has no NaN: missing numbers become null. */
    private static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static double nanMedian(double[][] v) {
        List<Double> vals = new ArrayList<>();
        for (double[] row : v) for (double x : row) if (!Double.isNaN(x)) vals.add(x);
        return vals.isEmpty() ? 0 : median(vals);
    }

    private static double median(List<Double> vals) {
        double[] s = vals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }
}
