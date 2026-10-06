/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether this deployment requires every uploaded file to be de-identified
 * before it is accepted ({@code libreclinica.ingest.deidentification.required},
 * env {@code LIBRECLINICA_INGEST_DEIDENTIFICATION_REQUIRED}).
 *
 * <p>The default is the value of {@code libreclinica.deployment.internet-facing}:
 * a deployment on the internet takes no file that has not been verified, and
 * the internal deployment, which receives from known acquisition PCs inside
 * the hospital, is unchanged. Controllers take this as an optional setter
 * dependency; when it is absent the mode is off, which keeps every
 * hand-built controller in the tests behaving as before.
 *
 * <p>When on: only E2E and DICOM are accepted, the patient id is a visible
 * subject's label and nothing else, the browser's SHA-256 confirmation must
 * match the bytes received, E2E patient chunks and DICOM headers are read by
 * the server, stored names are the neutral format, and the account-less
 * portals and the CRF file-item upload are closed. See
 * {@link DeidUploadGate}.
 */
@Component
public class DeidentificationPolicy {

    public static final String PROPERTY = "libreclinica.ingest.deidentification.required";

    private final boolean required;

    public DeidentificationPolicy(
            @Value("${libreclinica.ingest.deidentification.required:${libreclinica.deployment.internet-facing:false}}")
            boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    /** For tests and hand-built controllers. */
    public static DeidentificationPolicy of(boolean required) {
        return new DeidentificationPolicy(required);
    }

    /** True when {@code policy} is present and requires de-identification. */
    static boolean required(DeidentificationPolicy policy) {
        return policy != null && policy.isRequired();
    }
}
