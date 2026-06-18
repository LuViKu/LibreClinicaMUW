/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.retinal;

import java.util.Optional;

/**
 * Canonical string values for {@code retinal_inference_job.status}.
 *
 * <p>The column is {@code VARCHAR(20)} with no CHECK constraint, so the
 * vocabulary lives at the application layer. RetinalInferenceApiController
 * + PublicOctUploadController + the Python sidecar must agree on this
 * list — any mismatch surfaces only at read time, when callers compare
 * strings.
 *
 * <p>State transitions (per
 * {@code lc-muw-2026-06-10-retinal-inference-tables.xml}):
 * <pre>
 *   QUEUED     → SCREENING → SCREENED → SEGMENTING → DONE
 *               ↓
 *               (back to QUEUED on sidecar failure)
 *
 *   PARKED     — terminal-pending. The public OCT-upload portal lands
 *                here when no scheduled visit matches the scan date or
 *                the patient label is missing. A clinician later binds
 *                the job to an event_crf via the (Phase E follow-up)
 *                "Parkende Scans" view, which UPDATEs the row to
 *                event_crf_id + status='queued'.
 * </pre>
 */
public enum RetinalInferenceJobStatus {

    QUEUED("queued"),
    SCREENING("screening"),
    SCREENED("screened"),
    SEGMENTING("segmenting"),
    DONE("done"),
    /** Public-portal upload with no event_crf binding yet. */
    PARKED("parked");

    private final String dbValue;

    RetinalInferenceJobStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Optional<RetinalInferenceJobStatus> fromDbValue(String value) {
        if (value == null) return Optional.empty();
        for (RetinalInferenceJobStatus s : values()) {
            if (s.dbValue.equals(value)) return Optional.of(s);
        }
        return Optional.empty();
    }
}
