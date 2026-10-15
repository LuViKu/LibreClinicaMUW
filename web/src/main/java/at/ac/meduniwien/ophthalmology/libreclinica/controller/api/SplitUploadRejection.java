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
import java.util.Map;

import org.springframework.http.ResponseEntity;

/**
 * DR-041 - the split commit answers its success with a typed DTO
 * ({@link E2eSplitCommitResponse}) so the OpenAPI spec describes it; every other
 * outcome (400, 404, 409, 422, 502, 503) is thrown as this and written by
 * {@link ApiExceptionHandler} with the status and the body it carries
 * ({@link E2eSplitDuplicateResponse} for a duplicate, else {@code {message}}).
 */
public final class SplitUploadRejection extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Object body;

    public SplitUploadRejection(int status, Object body) {
        super(null, null, false, false); // no stack trace: this is a response, not a fault
        this.status = status;
        this.body = body;
    }

    public static SplitUploadRejection of(int status, String message) {
        return new SplitUploadRejection(status, Map.of("message", message));
    }

    public static SplitUploadRejection duplicate(String message, List<Long> existingIngestItemIds) {
        return new SplitUploadRejection(409, new E2eSplitDuplicateResponse(message, true, existingIngestItemIds));
    }

    /** An already built response (a guard's refusal) thrown instead of returned. */
    public static SplitUploadRejection from(ResponseEntity<?> response) {
        return new SplitUploadRejection(response.getStatusCode().value(), response.getBody());
    }

    public int status() {
        return status;
    }

    public Object body() {
        return body;
    }
}
