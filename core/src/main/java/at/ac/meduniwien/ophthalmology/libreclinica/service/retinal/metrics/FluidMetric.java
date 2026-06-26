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
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.LabelVolume;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io.NpzReader;

/**
 * DR-022 — fluid task metrics: per-biomarker total mm³, ETDRS central
 * 1/3/6 mm sub-totals, and per-B-scan area mm² traces for the SPA viewer.
 *
 * <p>Reads {@code fluidseg.npz} from {@code segDir} with key
 * {@code "segmentation"}, a {@code uint8} volume where label values map
 * to biomarkers: 0=background, 1=IRF (intra-retinal fluid), 2=SRF
 * (sub-retinal fluid), 3=PED (pigment-epithelium detachment).
 *
 * <p>The fovea anchor for ETDRS rings is currently the volume center
 * (volume-center MVP); a real fovea-detection step lands later.
 */
final class FluidMetric {

    private static final String FLUID_NPZ = "fluidseg.npz";
    private static final String FLUID_ENTRY = "segmentation";

    /** Diameter-named clinical ETDRS rings → radii in mm. */
    private static final double RADIUS_1MM = 0.5;
    private static final double RADIUS_3MM = 1.5;
    private static final double RADIUS_6MM = 3.0;

    private FluidMetric() { }

    static ComputedMetrics compute(Path segDir, PixelGeometry geom, String laterality) {
        Path npz = segDir.resolve(FLUID_NPZ);
        if (!Files.isRegularFile(npz)) {
            throw new MetricComputationException("missing artifact: " + npz);
        }
        LabelVolume vol;
        try {
            vol = NpzReader.read(npz, FLUID_ENTRY);
        } catch (IOException e) {
            throw new MetricComputationException(
                    "failed to read " + npz + " entry '" + FLUID_ENTRY + "'", e);
        }

        long irfVoxels = 0L;
        long srfVoxels = 0L;
        long pedVoxels = 0L;

        long irfRing1 = 0L;
        long srfRing1 = 0L;
        long pedRing1 = 0L;
        long irfRing3 = 0L;
        long srfRing3 = 0L;
        long pedRing3 = 0L;
        long irfRing6 = 0L;
        long srfRing6 = 0L;
        long pedRing6 = 0L;

        int dimZ = vol.dimZ();
        int dimY = vol.dimY();
        int dimX = vol.dimX();

        // Volume-center MVP: a real fovea localiser lands in a later wave.
        int foveaZ = dimZ / 2;
        int foveaX = dimX / 2;

        long[] irfPerBscan = new long[dimZ];
        long[] srfPerBscan = new long[dimZ];
        long[] pedPerBscan = new long[dimZ];

        boolean haveGeom = geom != null;
        double lateralMm = haveGeom ? geom.lateralMm() : 1.0;
        double axialMm   = haveGeom ? geom.axialMm()   : 1.0;
        double sliceMm   = haveGeom ? geom.sliceMm()   : 1.0;

        for (int z = 0; z < dimZ; z++) {
            double dzMm = (z - foveaZ) * sliceMm;
            for (int y = 0; y < dimY; y++) {
                for (int x = 0; x < dimX; x++) {
                    int label = vol.at(z, y, x);
                    if (label == 0) {
                        continue;
                    }
                    switch (label) {
                        case 1 -> { irfVoxels++; irfPerBscan[z]++; }
                        case 2 -> { srfVoxels++; srfPerBscan[z]++; }
                        case 3 -> { pedVoxels++; pedPerBscan[z]++; }
                        default -> { /* schema only emits 0..3 */ }
                    }
                    double dxMm = (x - foveaX) * lateralMm;
                    double distMm = Math.sqrt(dxMm * dxMm + dzMm * dzMm);
                    if (distMm <= RADIUS_6MM) {
                        switch (label) {
                            case 1 -> irfRing6++;
                            case 2 -> srfRing6++;
                            case 3 -> pedRing6++;
                            default -> { }
                        }
                        if (distMm <= RADIUS_3MM) {
                            switch (label) {
                                case 1 -> irfRing3++;
                                case 2 -> srfRing3++;
                                case 3 -> pedRing3++;
                                default -> { }
                            }
                            if (distMm <= RADIUS_1MM) {
                                switch (label) {
                                    case 1 -> irfRing1++;
                                    case 2 -> srfRing1++;
                                    case 3 -> pedRing1++;
                                    default -> { }
                                }
                            }
                        }
                    }
                }
            }
        }

        long totalVoxels = irfVoxels + srfVoxels + pedVoxels;
        double voxelVolMm3 = axialMm * lateralMm * sliceMm;
        double voxelAreaMm2 = axialMm * lateralMm;

        double irfMm3 = irfVoxels * voxelVolMm3;
        double srfMm3 = srfVoxels * voxelVolMm3;
        double pedMm3 = pedVoxels * voxelVolMm3;
        double totalMm3 = totalVoxels * voxelVolMm3;

        Map<String, Object> biomarkers = new LinkedHashMap<>();
        biomarkers.put("irf_mm3", irfMm3);
        biomarkers.put("srf_mm3", srfMm3);
        biomarkers.put("ped_mm3", pedMm3);
        biomarkers.put("total_mm3", totalMm3);

        Map<String, Object> etdrs = new LinkedHashMap<>();
        etdrs.put("central_1mm", ringMap(irfRing1, srfRing1, pedRing1, voxelVolMm3));
        etdrs.put("central_3mm", ringMap(irfRing3, srfRing3, pedRing3, voxelVolMm3));
        etdrs.put("central_6mm", ringMap(irfRing6, srfRing6, pedRing6, voxelVolMm3));

        Map<String, Object> etdrsCenter = new LinkedHashMap<>();
        etdrsCenter.put("bscan_z", foveaZ);
        etdrsCenter.put("ascan_x", foveaX);
        etdrsCenter.put("source", "volume-center-mvp");

        Map<String, Object> perBscan = new LinkedHashMap<>();
        perBscan.put("irf", scaleArray(irfPerBscan, voxelAreaMm2));
        perBscan.put("srf", scaleArray(srfPerBscan, voxelAreaMm2));
        perBscan.put("ped", scaleArray(pedPerBscan, voxelAreaMm2));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("biomarkers", biomarkers);
        payload.put("etdrs_mm3", etdrs);
        payload.put("etdrs_center", etdrsCenter);
        payload.put("voxel_volume_mm3", voxelVolMm3);
        payload.put("per_bscan_mm2", perBscan);
        payload.put("segmentation_file", FLUID_NPZ);
        if (laterality != null) {
            payload.put("laterality", laterality);
        }
        if (!haveGeom) {
            payload.put("geometry", "missing");
        }

        String unit = haveGeom ? "mm³" : "px³";
        BigDecimal primary = BigDecimal.valueOf(totalMm3).setScale(4, RoundingMode.HALF_UP);
        return new ComputedMetrics(primary, unit, payload);
    }

    private static Map<String, Object> ringMap(long irf, long srf, long ped, double voxelVolMm3) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("irf", irf * voxelVolMm3);
        m.put("srf", srf * voxelVolMm3);
        m.put("ped", ped * voxelVolMm3);
        m.put("total", (irf + srf + ped) * voxelVolMm3);
        return m;
    }

    private static double[] scaleArray(long[] counts, double voxelAreaMm2) {
        double[] out = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            out[i] = counts[i] * voxelAreaMm2;
        }
        return out;
    }
}
