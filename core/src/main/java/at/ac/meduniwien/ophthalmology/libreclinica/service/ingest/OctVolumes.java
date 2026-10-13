/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.util.Locale;

/**
 * DR-039 — which files are OCT volumes, the only thing retinal inference runs
 * on.
 *
 * <p>An {@code .e2e} always is one. A DICOM is one when it belongs to the
 * Ophthalmic Tomography IOD — SOP class {@value #OPT_SOP_CLASS_UID} or
 * Modality {@code OPT} — and has more than one frame: a single OPT frame is a
 * line scan, and the volume analyses need a stack of B-scans. The same rule
 * as {@code dicom_scp.tags.is_oct_volume} and the preprocess sidecar's
 * {@code not_oct_volume} check.
 */
public final class OctVolumes {

    /** Ophthalmic Tomography Image Storage (PS3.4 B.5). */
    public static final String OPT_SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.77.1.5.4";

    private OctVolumes() {}

    /**
     * @return TRUE / FALSE, or null when the object is of the OPT class but
     *         its frame count is unknown — a guess either way would be wrong
     *         for somebody
     */
    public static Boolean classify(String sopClassUid, String modality, Integer numberOfFrames) {
        boolean opt = OPT_SOP_CLASS_UID.equals(sopClassUid == null ? null : sopClassUid.trim())
                || "OPT".equals(modality == null ? null : modality.trim().toUpperCase(Locale.ROOT));
        if (!opt) return Boolean.FALSE;
        if (numberOfFrames == null) return null;
        return numberOfFrames > 1;
    }

    /** The sidecar's verdict when it gave one, else {@link #classify} of what it described. */
    public static Boolean of(DicomDescribeClient.Description d) {
        if (d == null) return null;
        if (d.octVolume() != null) return d.octVolume();
        return classify(d.sopClassUid(), d.modality(), d.numberOfFrames());
    }

    /**
     * Whether a file of this kind can be analysed: an {@code .e2e}, or a
     * DICOM classified as an OCT volume. An unclassified DICOM is not.
     */
    public static boolean isAnalysable(String kind, Boolean octVolume) {
        if (kind == null) return false;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if ("e2e".equals(k)) return true;
        return "dicom".equals(k) && Boolean.TRUE.equals(octVolume);
    }
}
