/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class DeidentificationPolicy implements org.springframework.beans.factory.InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DeidentificationPolicy.class);

    public static final String PROPERTY = "libreclinica.ingest.deidentification.required";

    private final boolean required;

    public DeidentificationPolicy(
            @Value("${libreclinica.ingest.deidentification.required:${libreclinica.deployment.internet-facing:false}}")
            boolean required) {
        this.required = required;
    }

    /** The ingest paths the mode closes, for the one startup line. */
    static final List<String> CLOSED_PATHS = List.of(
            "account-less upload portals (/public/upload, /public/oct-upload, /public/image-upload)",
            "DICOM C-STORE hand-off (/internal/dicom-ingest)",
            "Remidio pull scheduler and its manual trigger",
            "direct OCT upload (/event-crfs/{id}/oct-upload)",
            "CRF item file upload (/eventCrfs/{id}/items/{oid}/file)");

    /** Once, at startup: which ingest paths this mode switched off. */
    @Override
    public void afterPropertiesSet() {
        if (required) {
            LOG.warn("De-identification is required ({}): staff upload takes only verified E2E and DICOM; "
                    + "closed ingest paths: {}", PROPERTY, String.join("; ", CLOSED_PATHS));
        }
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
