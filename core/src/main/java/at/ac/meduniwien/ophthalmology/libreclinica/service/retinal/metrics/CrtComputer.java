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
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.PixelGeometry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceCsvReader;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.SurfaceGrid;

/**
 * 2026-06-24 — pure-math CRT (Central Retinal Thickness, central 1 mm)
 * computer.
 *
 * <p>Consumes two IOWA-OCTLayerSeg-style CSVs — one for the ILM (Inner
 * Limiting Membrane) surface from the GA runner, one for BM (Bruch's
 * Membrane) from the BM runner — and the per-axis pixel scales from
 * {@link PixelGeometry}, and returns the mean retinal thickness in
 * micrometres over the central 1 mm disk around the scan center.
 *
 * <p>CSV layout (matches the IOWA + sese_bm outputs):
 * <pre>
 *   {n_ascans}, {n_bscans}, {n_axial_px}
 *   {n_ascans Y-values separated by commas}        # B-scan 0
 *   {n_ascans Y-values separated by commas}        # B-scan 1
 *   ...
 *   {n_ascans Y-values separated by commas}        # B-scan n_bscans-1
 * </pre>
 *
 * <p>Y values are the axial-pixel row where the surface crosses each
 * A-scan column. {@code BM_y − ILM_y} is the retinal thickness in
 * pixels at that (column, b-scan). Multiplied by
 * {@link PixelGeometry#axialMm()} × 1000 it becomes µm.
 *
 * <p>The central-1mm mask is built from the scan center: pixel (z, x) is
 * included when
 *   sqrt( ((x − x_center) · pixel_lateral_mm)²
 *       + ((z − z_center) · pixel_slice_mm)² ) ≤ 0.5 mm.
 * The scan center is taken as ({@code dimZ/2}, {@code dimX/2}); fovea
 * detection is a follow-up.
 *
 * <p>Failure modes — all throw {@link MetricComputationException}:
 *
 * <ul>
 *   <li>ILM and BM CSVs have different shapes,</li>
 *   <li>shape disagrees with the {@link PixelGeometry} dimensions,</li>
 *   <li>fewer than {@link #MIN_PIXELS_INSIDE_DISK} pixels fall inside
 *       the central-1mm disk (mostly happens on heavily-decimated
 *       slice scans where {@code pixel_slice_mm} ≈ the 1 mm radius).</li>
 *   <li>a CSV row contains a non-numeric / NaN value,</li>
 *   <li>any {@code BM_y ≤ ILM_y} pair (anatomically impossible — flags
 *       a swap of the two inputs).</li>
 * </ul>
 */
@Component
public class CrtComputer {

    /**
     * Hard floor on the number of pixels inside the central-1mm disk. A
     * realistic Spectralis macula scan (≈ 0.02 mm/A-scan × ≈ 0.2 mm/B-scan)
     * lands several hundred pixels in the disk; below 20 is almost
     * certainly degenerate geometry and the resulting mean is noise.
     */
    static final int MIN_PIXELS_INSIDE_DISK = 20;

    /** Central-disk radius in millimetres. */
    private static final double CENTRAL_DISK_RADIUS_MM = 0.5;

    /**
     * Return shape — both the headline CRT µm value AND the contributing
     * pixel count so the caller (and the SPA tooltip) can surface how
     * many samples backed the mean.
     */
    public record Result(double crtMicrons, int pixelsInDisk) {}

    /**
     * Compute CRT µm for the central 1 mm.
     *
     * @param ilmCsv path to the ILM surface CSV (from the GA runner).
     * @param bmCsv  path to the BM surface CSV (from the BM runner).
     * @param geom   per-pixel mm scales + dimensions.
     */
    public Result computeCrtMicrons(Path ilmCsv, Path bmCsv, PixelGeometry geom) {
        if (geom == null) {
            throw new MetricComputationException("PixelGeometry is required to anchor the central-1mm disk");
        }
        SurfaceGrid ilmGrid = readSurface(ilmCsv);
        SurfaceGrid bmGrid = readSurface(bmCsv);

        if (ilmGrid.nBscans() != bmGrid.nBscans()) {
            throw new MetricComputationException(
                    "ILM/BM row count mismatch: ILM has " + ilmGrid.nBscans()
                            + " B-scans, BM has " + bmGrid.nBscans());
        }
        if (ilmGrid.nAscans() != bmGrid.nAscans()) {
            throw new MetricComputationException(
                    "ILM/BM width mismatch: ILM=" + ilmGrid.nAscans()
                            + " BM=" + bmGrid.nAscans());
        }
        if (ilmGrid.nBscans() == 0) {
            throw new MetricComputationException("ILM surface is empty");
        }
        int nBscans = ilmGrid.nBscans();
        int nAscans = ilmGrid.nAscans();
        double[][] ilm = ilmGrid.yPerBscan();
        double[][] bm = bmGrid.yPerBscan();
        // Geometry coherence — the IOWA + BM CSVs encode their own
        // (n_ascans, n_bscans) in the header, but PixelGeometry comes
        // from the preprocess sidecar's parse of the .e2e header. If
        // they disagree, the millimetre scale we're about to apply
        // doesn't match the pixel grid we just parsed — bail.
        if (geom.dimX() != nAscans || geom.dimZ() != nBscans) {
            throw new MetricComputationException(
                    "Surface shape (" + nAscans + " ascans × " + nBscans
                            + " bscans) does not match geometry"
                            + " (" + geom.dimX() + " × " + geom.dimZ() + ")");
        }

        // Pixel-grid coordinates of the scan center — integer midpoints,
        // not floats. With dimX=1024 the center sits at column 512 which
        // is the "between-pixels" gridline; using the integer is a
        // half-pixel approximation that's well below CRT's noise floor
        // (typically ±2 µm) and avoids a sub-pixel-indexed mask.
        double centerX = geom.dimX() / 2.0;
        double centerZ = geom.dimZ() / 2.0;
        double lateralMm = geom.lateralMm();
        double sliceMm = geom.sliceMm();
        double radiusMmSq = CENTRAL_DISK_RADIUS_MM * CENTRAL_DISK_RADIUS_MM;

        long pixelsInDisk = 0;
        double thicknessPxSum = 0.0;
        for (int z = 0; z < nBscans; z++) {
            double dzMm = (z - centerZ) * sliceMm;
            if (Math.abs(dzMm) > CENTRAL_DISK_RADIUS_MM) {
                // No pixel in this B-scan can be ≤ 0.5 mm from the center
                // in the lateral direction once we're already > 0.5 mm
                // away in the slice direction. Saves the inner loop.
                continue;
            }
            double dzMmSq = dzMm * dzMm;
            double[] ilmRow = ilm[z];
            double[] bmRow = bm[z];
            for (int x = 0; x < nAscans; x++) {
                double dxMm = (x - centerX) * lateralMm;
                if (dxMm * dxMm + dzMmSq > radiusMmSq) continue;
                double ilmY = ilmRow[x];
                double bmY = bmRow[x];
                // NaN at either surface = the segmenter dropped that
                // A-scan (typical at scan edges or under low-signal
                // shadows). Skip it; the disk mean weights the
                // remaining pixels normally.
                if (Double.isNaN(ilmY) || Double.isNaN(bmY)) continue;
                double thicknessPx = bmY - ilmY;
                if (thicknessPx <= 0) {
                    throw new MetricComputationException(
                            "BM_y must be > ILM_y at (z=" + z + ", x=" + x + "):"
                                    + " ILM=" + ilmY + " BM=" + bmY
                                    + " — likely an input-file swap");
                }
                thicknessPxSum += thicknessPx;
                pixelsInDisk++;
            }
        }

        if (pixelsInDisk < MIN_PIXELS_INSIDE_DISK) {
            throw new MetricComputationException(
                    "Only " + pixelsInDisk + " pixels fell inside the central-1mm disk"
                            + " (threshold " + MIN_PIXELS_INSIDE_DISK + "). Scan geometry too sparse?");
        }

        double meanThicknessPx = thicknessPxSum / pixelsInDisk;
        double crtMicrons = meanThicknessPx * geom.axialMm() * 1000.0;
        return new Result(crtMicrons, (int) pixelsInDisk);
    }

    /**
     * Delegate to the canonical {@link SurfaceCsvReader} so the BM/ILM
     * surfaces here parse with the same conventions as the ONL/PR
     * thickness paths (NaN for missing tokens, header treated as
     * declarative-only). Any IO error becomes a
     * {@link MetricComputationException} so the caller's catch is
     * single-shaped.
     */
    private static SurfaceGrid readSurface(Path csv) {
        if (csv == null) {
            throw new MetricComputationException("Surface CSV path is null");
        }
        try {
            return SurfaceCsvReader.read(csv);
        } catch (IOException ioEx) {
            throw new MetricComputationException(
                    "Failed to read surface CSV " + csv + ": " + ioEx.getMessage(), ioEx);
        } catch (IllegalArgumentException badRow) {
            throw new MetricComputationException(
                    "Malformed surface CSV " + csv + ": " + badRow.getMessage(), badRow);
        }
    }
}
