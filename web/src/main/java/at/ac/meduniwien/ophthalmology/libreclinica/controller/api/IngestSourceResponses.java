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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

/**
 * DR-040 phase 2 — how a refusal of {@link IngestSourceService} is answered,
 * the same way by every endpoint that calls it.
 *
 * <ul>
 *   <li>a source or location that is not there: <b>404</b>;</li>
 *   <li>a location that is disabled: <b>409</b>;</li>
 *   <li>a name already used, or a value that is not acceptable: <b>400</b>
 *       with the field named ({@link ValidationErrorBody});</li>
 *   <li>a database that could not do it: <b>500</b> with the SQLSTATE in the
 *       log and nothing of the statement's values in either.</li>
 * </ul>
 */
final class IngestSourceResponses {

    private static final Logger LOG = LoggerFactory.getLogger(IngestSourceResponses.class);

    private IngestSourceResponses() {}

    static ResponseEntity<?> refusal(IngestSourceService.Refusal r) {
        return switch (r.reason()) {
            case NOT_FOUND, LOCATION_NOT_FOUND -> ResponseEntity.status(404).body(Map.of("message", r.getMessage()));
            case LOCATION_UNAVAILABLE -> ResponseEntity.status(409).body(Map.of("message", r.getMessage()));
            case NAME_TAKEN, INVALID -> validationFailed(
                    List.of(new FieldError(r.field() == null ? "body" : r.field(), r.getMessage())));
        };
    }

    static ResponseEntity<?> failure(IngestSourceService.Failure f) {
        LOG.error("ingest sources: {}", f.getMessage(), f);
        return ResponseEntity.status(500).body(Map.of("message", "Could not " + f.what()));
    }

    static ResponseEntity<?> validationFailed(List<FieldError> errors) {
        return ResponseEntity.badRequest().body(new ValidationErrorBody("Validation failed", errors));
    }

    static ResponseEntity<?> missingBody() {
        return ResponseEntity.badRequest().body(new ValidationErrorBody(
                "Request body is required", List.of(new FieldError("body", "missing"))));
    }

    static ResponseEntity<?> notFound(String what) {
        return ResponseEntity.status(404).body(Map.of("message", what));
    }
}
