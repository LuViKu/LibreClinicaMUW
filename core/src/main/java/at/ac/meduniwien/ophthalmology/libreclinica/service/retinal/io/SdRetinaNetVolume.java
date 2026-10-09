/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import java.util.List;

/**
 * 2026-10-07 — an SD-RetinaNet volume segmentation (Fazekas et al., arXiv
 * 2509.20864) as read by {@link SdRetinaNetReader}: per-B-scan layer
 * boundaries plus bit-packed lesion masks.
 *
 * <p>{@code boundaries} is flat {@code [bscan][layer][ascan]}: the 0-based
 * depth row of each boundary, {@link Float#NaN} where the model reported
 * confidence 0.
 *
 * <p>{@code packedLesions} is flat {@code [bscan][row][col]}, one byte per
 * pixel in the lesionlib packing: the low bits hold a main-lesion id
 * ({@code i + 1} for {@code mainLesions.get(i)}, 0 = none; main lesions are
 * mutually exclusive) and overlay lesion {@code k} is bit {@code 7 - k}.
 * Main ids never reach the overlay bits, so masking with
 * {@link #mainMask()} recovers the main id whatever bit count the writer used.
 */
public record SdRetinaNetVolume(int nBscans,
                                int width,
                                int height,
                                List<String> layerNames,
                                float[] boundaries,
                                List<String> mainLesions,
                                List<String> overlayLesions,
                                byte[] packedLesions) {

    public float boundary(int bscan, int layer, int ascan) {
        return boundaries[(bscan * layerNames.size() + layer) * width + ascan];
    }

    public int packed(int bscan, int row, int col) {
        return packedLesions[(bscan * height + row) * width + col] & 0xFF;
    }

    /** Bit mask over the main-lesion id: every bit below the overlay bits. */
    public int mainMask() {
        return (1 << (8 - overlayLesions.size())) - 1;
    }

    /** Bit of overlay lesion {@code k}. */
    public static int overlayBit(int k) {
        return 1 << (7 - k);
    }
}
