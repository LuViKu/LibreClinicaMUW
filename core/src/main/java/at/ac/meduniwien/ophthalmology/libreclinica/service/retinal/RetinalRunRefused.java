/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

/**
 * DR-039 — a scan was refused for a reason the operator can act on, and the
 * message says which: the preprocess sidecar or the cluster answered 422 with
 * {@code {"detail": {"error", "message"}}}, or the requested task is not
 * validated for the scan's device. Distinct from the null that
 * {@link RemoteRetinalInferenceClient#runRemote} returns for an outage, so the
 * job records the reason instead of "returned null".
 */
public class RetinalRunRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient ScanSource source;

    /**
     * @param code    the sidecar's error code ({@code laterality_missing},
     *                {@code unsupported_device}, …)
     * @param message the text the job page shows
     * @param source  what the preprocess step reported about the scan, or null
     *                when it did not get that far
     */
    public RetinalRunRefused(String code, String message, ScanSource source) {
        super(message);
        this.code = code;
        this.source = source;
    }

    public String code() {
        return code;
    }

    public ScanSource source() {
        return source;
    }
}
